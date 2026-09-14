#!/usr/bin/env bash
#
# Two-tier banking, Phase 3: settlement and supervision (TWO_TIER_BANKING_PLAN.md §13).
#
# Phase 3 is done when invariants I1-I5 hold against the running database after a mixed run of
# intra-bank and inter-bank payments, including a concurrent pair of inter-bank payments from
# one institution. This runs exactly that: it builds two banks with two customers each, moves
# money within and between them, drives one bank into its net debit cap, fires two payments at
# once, and then checks every invariant in SQL against the database rather than asking the
# application whether it thinks it is consistent.
#
# Needs a running backend on an EMPTY database, curl, python3 and psql.
#
#   API=http://localhost:8080/api/v1 ./scripts/verify-two-tier-phase3.sh
#
# Exits non-zero if any check fails.

set -uo pipefail

API="${API:-http://localhost:8080/api/v1}"
PGHOST="${PGHOST:-localhost}"
PGUSER="${PGUSER:-postgres}"
PGDATABASE="${PGDATABASE:-mldsa}"
export PGPASSWORD="${PGPASSWORD:-postgres}"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

PASS=0
FAIL=0
STATUS=""
RESPONSE=""
PASSWORD='pass1234'

# The cap the backend is configured with (app.settlement.net-debit-cap).
NET_DEBIT_CAP="${NET_DEBIT_CAP:-5000000}"

call() {
  local method=$1 path=$2 token=${3:-} body=${4:-}
  local args=(-s -o "$WORK_DIR/body" -w '%{http_code}' -X "$method" "$API$path")
  [[ -n $token ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n $body ]] && args+=(-H 'Content-Type: application/json' -d "$body")
  STATUS=$(curl "${args[@]}")
  RESPONSE=$(cat "$WORK_DIR/body")
}

field() {
  python3 -c 'import json, sys; d = json.loads(sys.argv[2]); print(eval(sys.argv[1]))' "$1" "$RESPONSE"
}

check() {
  local description=$1 want=$2 message=${3:-}
  if [[ $STATUS == "$want" && ( -z $message || $RESPONSE == *"$message"* ) ]]; then
    PASS=$((PASS + 1)); printf '  PASS  %s  [%s]\n' "$description" "$STATUS"
  else
    FAIL=$((FAIL + 1)); printf '  FAIL  %s  — wanted %s%s, got %s: %s\n' \
      "$description" "$want" "${message:+ \"$message\"}" "$STATUS" "$RESPONSE"
  fi
}

assert() {
  local description=$1 condition=$2
  if [[ $(field "$condition" 2>/dev/null) == "True" ]]; then
    PASS=$((PASS + 1)); printf '  PASS  %s\n' "$description"
  else
    FAIL=$((FAIL + 1)); printf '  FAIL  %s  — condition %s over: %s\n' "$description" "$condition" "$RESPONSE"
  fi
}

sql() {
  psql -h "$PGHOST" -U "$PGUSER" -d "$PGDATABASE" -tAc "$1" | tr -d '[:space:]'
}

# sql_true DESCRIPTION QUERY — the query must return a single boolean true.
sql_true() {
  local description=$1 query=$2 result
  result=$(sql "$query")
  if [[ $result == t ]]; then
    PASS=$((PASS + 1)); printf '  PASS  %s\n' "$description"
  else
    FAIL=$((FAIL + 1)); printf '  FAIL  %s  — query returned "%s"\n' "$description" "$result"
  fi
}

# sql_equals DESCRIPTION QUERY EXPECTED
sql_equals() {
  local description=$1 query=$2 expected=$3 result
  result=$(sql "$query")
  if [[ $(python3 -c "print(float('$result') == float('$expected'))") == True ]]; then
    PASS=$((PASS + 1)); printf '  PASS  %s  [%s]\n' "$description" "$result"
  else
    FAIL=$((FAIL + 1)); printf '  FAIL  %s  — wanted %s, got %s\n' "$description" "$expected" "$result"
  fi
}

login() {
  call POST /auth/login "" "{\"username\":\"$1\",\"password\":\"$PASSWORD\"}"
  [[ $STATUS == 200 ]] || { echo "Login failed for $1: $STATUS $RESPONSE" >&2; exit 2; }
  field 'd["token"]'
}

pay() {
  call POST /payments "$1" "{\"toAccountNumber\":\"$2\",\"amount\":$3,\"description\":\"$4\"}"
}

deposit() {
  call POST /payments/deposits "$1" "{\"amount\":$2,\"description\":\"$3\"}"
}

position_of() {
  sql "select coalesce(sum(case when p.direction = 'CREDIT' then p.amount else -p.amount end), 0)
       from ledger.postings p
       join ledger.accounts a on a.account_id = p.account_id
       join identity.users i on i.user_id = a.institution_id
       where a.account_type = 'SETTLEMENT' and i.institution_code = '$1'"
}

# expect_position DESCRIPTION INSTITUTION_CODE EXPECTED — read straight from the ledger.
expect_position() {
  local description=$1 code=$2 expected=$3 actual
  actual=$(position_of "$code")
  if [[ $(python3 -c "print(float('$actual') == float('$expected'))") == True ]]; then
    PASS=$((PASS + 1)); printf '  PASS  %s  [%s]\n' "$description" "$actual"
  else
    FAIL=$((FAIL + 1)); printf '  FAIL  %s  — wanted %s, got %s\n' "$description" "$expected" "$actual"
  fi
}

echo "== Precondition: empty database"
call GET /users/bootstrap
if [[ $STATUS != 200 || $(field 'd["open"]') != "True" ]]; then
  echo "Bootstrap is not open ($STATUS $RESPONSE). Run this against an empty database." >&2
  exit 2
fi

call POST /users "" "{\"username\":\"cb-overseer\",\"password\":\"$PASSWORD\",\"role\":\"BANK\"}"
check "Bootstrap the Central Bank overseer" 201
CB=$(login cb-overseer)

echo "== Two banks, two customers each"
call POST /admin/institutions "$CB" "{\"username\":\"alpha-bank\",\"password\":\"$PASSWORD\",\"institutionCode\":\"ALPHA\"}"
check "License Alpha Bank" 201
call POST /admin/institutions "$CB" "{\"username\":\"beta-bank\",\"password\":\"$PASSWORD\",\"institutionCode\":\"BETA\"}"
check "License Beta Bank" 201

ALPHA=$(login alpha-bank)
BETA=$(login beta-bank)

call POST /institution/customers "$ALPHA" "{\"username\":\"ann\",\"password\":\"$PASSWORD\"}"
ANN_ACCOUNT=$(field 'd["accountNumber"]')
call POST /institution/customers "$ALPHA" "{\"username\":\"amos\",\"password\":\"$PASSWORD\"}"
AMOS_ACCOUNT=$(field 'd["accountNumber"]')
call POST /institution/customers "$BETA" "{\"username\":\"ben\",\"password\":\"$PASSWORD\"}"
BEN_ACCOUNT=$(field 'd["accountNumber"]')
call POST /institution/customers "$BETA" "{\"username\":\"bea\",\"password\":\"$PASSWORD\"}"
BEA_ACCOUNT=$(field 'd["accountNumber"]')
check "Both banks open their customers' accounts" 201

ANN=$(login ann)
AMOS=$(login amos)
BEN=$(login ben)

deposit "$ANN" 100000 "Opening deposit"
check "Ann deposits 100,000" 201
deposit "$BEN" 50000 "Opening deposit"
check "Ben deposits 50,000" 201

echo "== Intra-bank: two postings, settlement untouched"
pay "$ANN" "$AMOS_ACCOUNT" 20000 "Same bank"
check "Ann pays Amos, both at Alpha" 201
INTRA_REF=$(field 'd["transactionRef"]')
sql_equals "…writes exactly two postings" \
  "select count(*) from ledger.postings where transaction_ref = '$INTRA_REF'" 2
sql_equals "…and none of them on a settlement account" \
  "select count(*) from ledger.postings p join ledger.accounts a on a.account_id = p.account_id
   where p.transaction_ref = '$INTRA_REF' and a.account_type = 'SETTLEMENT'" 0
expect_position "Alpha's position is untouched" ALPHA 0

echo "== Inter-bank: four postings, one reference"
pay "$ANN" "$BEN_ACCOUNT" 30000 "Across banks"
check "Ann pays Ben at Beta" 201
INTER_REF=$(field 'd["transactionRef"]')
assert "…and the customer is shown the receiving bank" 'd["toInstitutionCode"] == "BETA"'
sql_equals "…writes exactly four postings under one reference" \
  "select count(*) from ledger.postings where transaction_ref = '$INTER_REF'" 4
sql_true "…debiting the payer and their bank, crediting the payee and theirs" \
  "select (
     select count(*) from ledger.postings p
     join ledger.accounts a on a.account_id = p.account_id
     where p.transaction_ref = '$INTER_REF' and a.account_type = 'SETTLEMENT' and p.direction = 'DEBIT') = 1
   and (
     select count(*) from ledger.postings p
     join ledger.accounts a on a.account_id = p.account_id
     where p.transaction_ref = '$INTER_REF' and a.account_type = 'SETTLEMENT' and p.direction = 'CREDIT') = 1"
expect_position "Alpha is now a net debtor of 30,000" ALPHA -30000
expect_position "Beta is owed 30,000" BETA 30000

pay "$BEN" "$ANN_ACCOUNT" 10000 "Back the other way"
check "Ben pays Ann, settling in the opposite direction" 201
expect_position "Alpha's position recovers to -20,000" ALPHA -20000
expect_position "Beta's falls to 20,000" BETA 20000

echo "== The net debit cap"
deposit "$ANN" 5000000 "Large deposit"
check "Ann deposits 5,000,000" 201

# Alpha stands at -20,000, so a payment of 4,990,000 would take it past a 5,000,000 cap.
pay "$ANN" "$BEN_ACCOUNT" 4990000 "Over the cap"
check "A payment breaching Alpha's cap is refused" 400 "could not be settled"
assert "…telling the customer nothing about which bank or why" \
  'd["message"] == "The payment could not be settled"'
expect_position "…and moving no money" ALPHA -20000

call GET /payments "$ANN"
check "The customer's own payment history loads" 200
assert "…shows only the generic reason" \
  'any(p["failureReason"] == "The payment could not be settled" for p in d["content"])'
assert "…and carries no failure detail at all" \
  'all("failureDetail" not in p for p in d["content"])'

call GET /institution/payments "$ALPHA"
check "Alpha sees its own customers' payments" 200
assert "…including the specific cause of the refusal" \
  'any(p["failureDetail"] and "net debit cap" in p["failureDetail"] for p in d["content"])'

call GET /admin/settlement/refusals "$CB"
check "The Central Bank sees what could not be settled" 200
assert "…with the cause spelled out" 'len(d["content"]) == 1 and "net debit cap" in d["content"][0]["detail"]'
assert "…and no customer named in it" '"ann" not in json.dumps(d["content"][0])'

pay "$ANN" "$BEN_ACCOUNT" 4000000 "Just inside the cap"
check "A payment inside the cap still settles" 201
expect_position "Alpha's position is now -4,020,000" ALPHA -4020000

echo "== A concurrent pair of inter-bank payments from one institution"
deposit "$AMOS" 600000 "Funding"
check "Amos is funded" 201

# Headroom is 980,000. Two payments of 500,000 fired together are affordable one at a time and
# not together, so exactly one must be refused — the case a naive read-then-write would let
# both through.
curl -s -o "$WORK_DIR/race-ann" -w '%{http_code}' -X POST "$API/payments" \
  -H "Authorization: Bearer $ANN" -H 'Content-Type: application/json' \
  -d "{\"toAccountNumber\":\"$BEN_ACCOUNT\",\"amount\":500000,\"description\":\"Race A\"}" \
  > "$WORK_DIR/race-ann.code" &
curl -s -o "$WORK_DIR/race-amos" -w '%{http_code}' -X POST "$API/payments" \
  -H "Authorization: Bearer $AMOS" -H 'Content-Type: application/json' \
  -d "{\"toAccountNumber\":\"$BEA_ACCOUNT\",\"amount\":500000,\"description\":\"Race B\"}" \
  > "$WORK_DIR/race-amos.code" &
wait

RACE_A=$(cat "$WORK_DIR/race-ann.code")
RACE_B=$(cat "$WORK_DIR/race-amos.code")
SUCCEEDED=0
[[ $RACE_A == 201 ]] && SUCCEEDED=$((SUCCEEDED + 1))
[[ $RACE_B == 201 ]] && SUCCEEDED=$((SUCCEEDED + 1))
if [[ $SUCCEEDED == 1 ]]; then
  PASS=$((PASS + 1)); printf '  PASS  Exactly one of the two concurrent payments settled  [%s, %s]\n' "$RACE_A" "$RACE_B"
else
  FAIL=$((FAIL + 1)); printf '  FAIL  Both or neither settled  [%s, %s]\n' "$RACE_A" "$RACE_B"
fi
expect_position "…leaving Alpha inside its cap" ALPHA -4520000
sql_true "…and never past it" "select abs($(position_of ALPHA)) <= $NET_DEBIT_CAP"

echo "== Invariants against the database (I1-I5)"
sql_true "I1  every posting effect sums to the total deposited" \
  "select (select coalesce(sum(case when direction = 'CREDIT' then amount else -amount end), 0)
           from ledger.postings)
        = (select coalesce(sum(amount), 0) from payments.deposits)"

sql_true "I2  settlement positions sum to zero" \
  "select coalesce(sum(case when p.direction = 'CREDIT' then p.amount else -p.amount end), 0) = 0
   from ledger.postings p
   join ledger.accounts a on a.account_id = p.account_id
   where a.account_type = 'SETTLEMENT'"

sql_true "I3  customer balances sum to the total deposited" \
  "select (select coalesce(sum(case when p.direction = 'CREDIT' then p.amount else -p.amount end), 0)
           from ledger.postings p
           join ledger.accounts a on a.account_id = p.account_id
           where a.account_type = 'CUSTOMER')
        = (select coalesce(sum(amount), 0) from payments.deposits)"

sql_true "I4a every payment's postings net to zero" \
  "select not exists (
     select 1 from payments.payments pay
     join ledger.postings p on p.transaction_ref = pay.transaction_ref
     where pay.transaction_ref is not null
     group by pay.transaction_ref
     having sum(case when p.direction = 'CREDIT' then p.amount else -p.amount end) <> 0)"

sql_true "I4b every deposit's postings net to its amount" \
  "select not exists (
     select 1 from payments.deposits d
     join ledger.postings p on p.transaction_ref = d.transaction_ref
     group by d.transaction_ref, d.amount
     having sum(case when p.direction = 'CREDIT' then p.amount else -p.amount end) <> d.amount)"

sql_true "I5  no intra-bank payment ever touches a settlement account" \
  "select not exists (
     select 1
     from payments.payments pay
     join ledger.accounts fa on fa.account_id = pay.from_account_id
     join ledger.accounts ta on ta.account_id = pay.to_account_id
     join ledger.postings p on p.transaction_ref = pay.transaction_ref
     join ledger.accounts pa on pa.account_id = p.account_id
     where pay.transaction_ref is not null
       and fa.institution_id = ta.institution_id
       and pa.account_type = 'SETTLEMENT')"

sql_true "…and every payment has two postings within a bank, four across banks" \
  "select not exists (
     select 1
     from payments.payments pay
     join ledger.accounts fa on fa.account_id = pay.from_account_id
     join ledger.accounts ta on ta.account_id = pay.to_account_id
     join ledger.postings p on p.transaction_ref = pay.transaction_ref
     where pay.transaction_ref is not null
     group by pay.transaction_ref, fa.institution_id, ta.institution_id
     having count(*) <> (case when fa.institution_id = ta.institution_id then 2 else 4 end))"

echo "== The Central Bank's own view agrees"
call GET /admin/settlement "$CB"
check "Settlement view loads" 200
assert "…reports the positions as balanced (I2)" 'd["balanced"] is True and float(d["positionsSum"]) == 0'
assert "…lists only inter-bank movements, naming banks and not customers" \
  'len(d["movements"]["content"]) == 4 and all(m["fromInstitutionCode"] != m["toInstitutionCode"] for m in d["movements"]["content"])'
assert "…and no customer account number appears among them" \
  "all('$ANN_ACCOUNT' not in json.dumps(m) for m in d['movements']['content'])"

call GET /admin/summary "$CB"
check "Summary loads" 200
assert "…carrying the I2 check" 'd["settlementBalanced"] is True and float(d["settlementPositionsSum"]) == 0'

call GET /institution/summary "$ALPHA"
check "Alpha sees its own position and headroom" 200
assert "…matching the ledger" \
  'float(d["settlementPosition"]) == -4520000 and float(d["headroom"]) == float(d["netDebitCap"]) - 4520000'

call GET /institution/summary "$BETA"
assert "Beta sees the opposite position" 'float(d["settlementPosition"]) == 4520000'

echo
echo "Passed: $PASS   Failed: $FAIL"
[[ $FAIL -eq 0 ]]
