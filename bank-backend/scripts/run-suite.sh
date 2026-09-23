#!/usr/bin/env bash
#
# Runs one verification suite end to end against a throwaway database.
#
# The suites each need a backend that was started on an EMPTY database, so doing this by
# hand means recreating the database, restarting the jar, waiting for it, and only then
# running the script — in that order, every time. Getting the order wrong produces a
# cascade of failures that look like code faults and are not.
#
#   ./scripts/run-suite.sh verify-two-tier-phase3.sh [dbname]
#
# Exits with the suite's own exit code.
set -uo pipefail

SCRIPT=${1:?usage: run-suite.sh <verify-script> [dbname]}
DB=${2:-mldsa_suite}
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR=$(ls "$HERE"/target/mldsa-*.jar 2>/dev/null | head -1)
export PGPASSWORD="${PGPASSWORD:-postgres}"
PGUSER="${PGUSER:-postgres}"
PGHOST="${PGHOST:-localhost}"

[[ -f $JAR ]] || { echo "No jar in target/ — run ./mvnw package first"; exit 1; }

# Kill by pid rather than `pkill -f`: this script's own command line contains the jar name,
# so a pattern match would find, and kill, the shell running it.
stop_backend() {
  local pid
  pid=$(pgrep -f "[m]ldsa-0.0.1-SNAPSHOT.jar" | head -1)
  [[ -n $pid ]] && kill "$pid" 2>/dev/null
  while pgrep -f "[m]ldsa-0.0.1-SNAPSHOT.jar" >/dev/null; do sleep 1; done
}

stop_backend
psql -h "$PGHOST" -U "$PGUSER" -qc "drop database if exists $DB" >/dev/null 2>&1
psql -h "$PGHOST" -U "$PGUSER" -qc "create database $DB" >/dev/null 2>&1

# setsid so the backend outlives this script's process group.
SPRING_DATASOURCE_URL="jdbc:postgresql://$PGHOST:5432/$DB" \
  setsid java -jar "$JAR" > "/tmp/$DB.log" 2>&1 < /dev/null &

for _ in $(seq 1 60); do
  curl -sf -o /dev/null "http://localhost:8080/api/v1/users/bootstrap" && break
  sleep 2
done
curl -sf -o /dev/null "http://localhost:8080/api/v1/users/bootstrap" || {
  echo "Backend never came up — see /tmp/$DB.log"; exit 1; }

PGDATABASE="$DB" "$HERE/scripts/$SCRIPT"
RESULT=$?
stop_backend
psql -h "$PGHOST" -U "$PGUSER" -qc "drop database if exists $DB" >/dev/null 2>&1
exit $RESULT
