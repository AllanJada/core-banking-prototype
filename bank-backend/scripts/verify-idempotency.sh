#!/usr/bin/env bash
#
# Idempotency keys: a retried request is answered, not re-executed (REMAINING-WORK.md §2.2).
#
# A client whose request times out cannot tell whether the payment happened, so it retries.
# Without a key that retry pays again. This builds two banks and some customers, then sends
# the same request twice on purpose — by retry and by racing two at once — and checks against
# the database that the money moved exactly once.
#
# The deposit case is the one that matters most: a repeated deposit does not merely move money
# twice, it creates money that was never handed over, which is precisely what funding deposits
# from the institution's till was meant to make impossible.
#
# Needs a running backend on an EMPTY database, curl, python3 and psql.
#
#   API=http://localhost:8080/api/v1 ./scripts/verify-idempotency.sh
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
HEADERS=""
PASSWORD='pass1234'

# call METHOD PATH [TOKEN] [BODY] [IDEMPOTENCY-KEY]
call() {
  local method=$1 path=$2 token=${3:-} body=${4:-} key=${5:-}
  local args=(-s -o "$WORK_DIR/body" -D "$WORK_DIR/headers" -w '%{http_code}' -X "$method" "$API$path")
  [[ -n $token ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n $key ]] && args+=(-H "Idempotency-Key: $key")
  [[ -n $body ]] && args+=(-H 'Content-Type: application/json' -d "$body")
  STATUS=$(curl "${args[@]}")
  RESPONSE=$(cat "$WORK_DIR/body")
  HEADERS=$(cat "$WORK_DIR/headers")
}

field() {
  python3 -c 'import json, sys; d = json.loads(sys.argv[2]); print(eval(sys.argv[1]))' "$1" "$RESPONSE"
}

check() {
  local description=$1 want=$2
  if [[ $STATUS == "$want" ]]; then
    PASS=$((PASS + 1)); printf '  PASS  %s  [%s]\n' "$description" "$STATUS"
  else
    FAIL=$((FAIL + 1)); printf '  FAIL  %s  — wanted %s, got %s: %s\n' "$description" "$want" "$STATUS" "$RESPONSE"
  fi
}

assert() {
  local description=$1 ok=$2 detail=${3:-}
  if [[ $ok == 0 ]]; then
    PASS=$((PASS + 1)); printf '  PASS  %s\n' "$description"
  else
    FAIL=$((FAIL + 1)); printf '  FAIL  %s%s\n' "$description" "${detail:+ — $detail}"
  fi
}

sql() {
  psql -h "$PGHOST" -U "$PGUSER" -d "$PGDATABASE" -tAc "$1"
}

sql_is() {
  local description=$1 query=$2 want=$3
  local got
  got=$(sql "$query")
  if [[ $got == "$want" ]]; then
    PASS=$((PASS + 1)); printf '  PASS  %s  [%s]\n' "$description" "$got"
  else
    FAIL=$((FAIL + 1)); printf '  FAIL  %s  — wanted %s, got %s\n' "$description" "$want" "$got"
  fi
}

login() {
  call POST /auth/login "" "{\"username\":\"$1\",\"password\":\"$PASSWORD\"}"
  [[ $STATUS == 200 ]] || { echo "Login failed for $1: $STATUS $RESPONSE" >&2; exit 2; }
  field 'd["token"]'
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

call POST /admin/institutions "$CB" "{\"username\":\"alpha-bank\",\"password\":\"$PASSWORD\",\"institutionCode\":\"ALPHA\"}"
check "License Alpha Bank" 201
ALPHA=$(login alpha-bank)

call POST /institution/customers "$ALPHA" "{\"username\":\"ann\",\"password\":\"$PASSWORD\"}"
ANN_ACCOUNT=$(field 'd["accountNumber"]')
ANN_ID=$(field 'd["userId"]')
call POST /institution/customers "$ALPHA" "{\"username\":\"amos\",\"password\":\"$PASSWORD\"}"
AMOS_ACCOUNT=$(field 'd["accountNumber"]')
check "Alpha opens two customer accounts" 201
ANN=$(login ann)

echo "== A repeated deposit does not create money"
# The worst case for a duplicate: a teller double-clicking at the counter.
DEP_KEY="dep-$(date +%s%N)"
call POST "/institution/customers/$ANN_ID/deposits" "$ALPHA" '{"amount":500000,"description":"Counter"}' "$DEP_KEY"
check "First deposit is taken" 201
FIRST_DEPOSIT_ID=$(field 'd["depositId"]')

call POST "/institution/customers/$ANN_ID/deposits" "$ALPHA" '{"amount":500000,"description":"Counter"}' "$DEP_KEY"
check "…the retry is answered, not taken again" 201
assert "…with the same deposit id, so it is the first answer being replayed" \
  "$([[ $(field 'd["depositId"]') == "$FIRST_DEPOSIT_ID" ]] && echo 0 || echo 1)"
assert "…and says so with Idempotent-Replay" \
  "$(grep -qi '^idempotent-replay: true' <<<"$HEADERS" && echo 0 || echo 1)"

sql_is "…leaving exactly one deposit row" \
  "select count(*) from payments.deposits" 1
sql_is "…and Ann holding 500,000 rather than 1,000,000" \
  "select coalesce(sum(case when p.direction = 'CREDIT' then p.amount else -p.amount end), 0)
   from ledger.postings p
   join ledger.accounts a on a.account_id = p.account_id
   where a.account_number = '$ANN_ACCOUNT'" 500000.00
sql_is "…and the till debited once, not twice" \
  "select coalesce(sum(case when p.direction = 'CREDIT' then p.amount else -p.amount end), 0)
   from ledger.postings p
   join ledger.accounts a on a.account_id = p.account_id
   where a.account_type = 'CASH'" -500000.00

echo "== A repeated payment pays once"
PAY_KEY="pay-$(date +%s%N)"
call POST /payments "$ANN" "{\"toAccountNumber\":\"$AMOS_ACCOUNT\",\"amount\":100000,\"description\":\"Once\"}" "$PAY_KEY"
check "First payment goes through" 201
FIRST_PAYMENT_ID=$(field 'd["paymentId"]')

call POST /payments "$ANN" "{\"toAccountNumber\":\"$AMOS_ACCOUNT\",\"amount\":100000,\"description\":\"Once\"}" "$PAY_KEY"
check "…the retry is answered, not paid again" 201
assert "…with the same payment id" \
  "$([[ $(field 'd["paymentId"]') == "$FIRST_PAYMENT_ID" ]] && echo 0 || echo 1)"
sql_is "…leaving exactly one completed payment" \
  "select count(*) from payments.payments where status = 'COMPLETED'" 1
sql_is "…and Amos paid once" \
  "select coalesce(sum(case when p.direction = 'CREDIT' then p.amount else -p.amount end), 0)
   from ledger.postings p
   join ledger.accounts a on a.account_id = p.account_id
   where a.account_number = '$AMOS_ACCOUNT'" 100000.00

echo "== The same key with a different request is refused, not answered"
call POST /payments "$ANN" "{\"toAccountNumber\":\"$AMOS_ACCOUNT\",\"amount\":999,\"description\":\"Different\"}" "$PAY_KEY"
check "A key reused for a different body is rejected" 422
sql_is "…and still only one completed payment exists" \
  "select count(*) from payments.payments where status = 'COMPLETED'" 1

echo "== Two identical requests racing each other"
# Not a retry after a response, but a genuine duplicate in flight: the case a check that read
# before writing would let through. Exactly one must execute; the other is either told it is in
# progress or handed the first one's answer, and both are correct outcomes.
RACE_KEY="race-$(date +%s%N)"
for n in 1 2; do
  curl -s -o "$WORK_DIR/race-$n" -w '%{http_code}' -X POST "$API/payments" \
    -H "Authorization: Bearer $ANN" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $RACE_KEY" \
    -d "{\"toAccountNumber\":\"$AMOS_ACCOUNT\",\"amount\":50000,\"description\":\"Race\"}" \
    > "$WORK_DIR/race-$n.code" &
done
wait
RACE_1=$(cat "$WORK_DIR/race-1.code")
RACE_2=$(cat "$WORK_DIR/race-2.code")
CREATED=0
[[ $RACE_1 == 201 ]] && CREATED=$((CREATED + 1))
[[ $RACE_2 == 201 ]] && CREATED=$((CREATED + 1))
assert "Neither duplicate was refused outright  [$RACE_1, $RACE_2]" \
  "$([[ $RACE_1 =~ ^(201|409)$ && $RACE_2 =~ ^(201|409)$ ]] && echo 0 || echo 1)"
sql_is "…and the payment was made exactly once" \
  "select count(*) from payments.payments where status = 'COMPLETED' and description = 'Race'" 1

echo "== A refused request gives its key back"
# A refusal moved no money, so the same request must be retryable once the reason is gone.
BROKE_KEY="broke-$(date +%s%N)"
call POST /payments "$ANN" "{\"toAccountNumber\":\"$AMOS_ACCOUNT\",\"amount\":9999999,\"description\":\"Too much\"}" "$BROKE_KEY"
check "A payment beyond the balance is refused" 400
sql_is "…and its key is not left claimed" \
  "select count(*) from payments.idempotency_keys where idempotency_key = '$BROKE_KEY'" 0
call POST /payments "$ANN" "{\"toAccountNumber\":\"$AMOS_ACCOUNT\",\"amount\":1000,\"description\":\"Affordable\"}" "$BROKE_KEY"
check "…so the key can be used again" 201

echo "== Keys are scoped to the caller"
SHARED_KEY="shared-$(date +%s%N)"
call POST /payments "$ANN" "{\"toAccountNumber\":\"$AMOS_ACCOUNT\",\"amount\":1500,\"description\":\"Ann's\"}" "$SHARED_KEY"
check "Ann uses a key" 201
AMOS=$(login amos)
call POST /payments "$AMOS" "{\"toAccountNumber\":\"$ANN_ACCOUNT\",\"amount\":1500,\"description\":\"Amos's\"}" "$SHARED_KEY"
check "…and Amos may use the same string without being given Ann's answer" 201
assert "…because the two produced different payments" \
  "$([[ $(field 'd["description"]') == "Amos's" ]] && echo 0 || echo 1)" "$(field 'd["description"]')"

echo "== Shape of the key itself"
call POST /payments "$ANN" "{\"toAccountNumber\":\"$AMOS_ACCOUNT\",\"amount\":100,\"description\":\"Short key\"}" "abc"
check "A key too short to be an identifier is refused" 400

echo "== Requests without a key are unaffected"
BEFORE=$(sql "select count(*) from payments.payments where status = 'COMPLETED'")
call POST /payments "$ANN" "{\"toAccountNumber\":\"$AMOS_ACCOUNT\",\"amount\":250,\"description\":\"No key\"}"
check "A payment with no Idempotency-Key still works" 201
call POST /payments "$ANN" "{\"toAccountNumber\":\"$AMOS_ACCOUNT\",\"amount\":250,\"description\":\"No key\"}"
check "…and is not deduplicated, because nothing was asked of it" 201
sql_is "…so two more payments exist" \
  "select count(*) from payments.payments where status = 'COMPLETED'" "$((BEFORE + 2))"

echo "== The ledger is still sound"
sql_is "I1  every posting in the ledger nets to zero" \
  "select coalesce(sum(case when direction = 'CREDIT' then amount else -amount end), 0)
   from ledger.postings" 0.00
sql_is "I4b every deposit's postings net to zero" \
  "select count(*) from (
     select d.transaction_ref from payments.deposits d
     join ledger.postings p on p.transaction_ref = d.transaction_ref
     group by d.transaction_ref
     having sum(case when p.direction = 'CREDIT' then p.amount else -p.amount end) <> 0) offenders" 0

printf '\nPassed: %d   Failed: %d\n' "$PASS" "$FAIL"
[[ $FAIL == 0 ]]
