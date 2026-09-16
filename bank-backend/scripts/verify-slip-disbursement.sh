#!/usr/bin/env bash
#
# Payroll disbursement: a slip's ISO 20022 instruction actually moves money.
#
# A slip has always carried a pain.001 describing a credit transfer. This checks that the
# description is now executed — and, just as importantly, that it is executed at exactly one
# moment and not before: sending a slip moves nothing, rejecting it moves nothing, and only
# the receiving institution's approval disburses it.
#
# Needs a running backend on an EMPTY database, curl, psql and python3.
#
#   API=http://localhost:8080/api/v1 ./scripts/verify-slip-disbursement.sh
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

sql() { psql -h "$PGHOST" -U "$PGUSER" -d "$PGDATABASE" -tAc "$1" | tr -d '[:space:]'; }

sql_equals() {
  local description=$1 query=$2 expected=$3 result
  result=$(sql "$query")
  if [[ $(python3 -c "
import sys
a, b = sys.argv[1], sys.argv[2]
try:    print(float(a) == float(b))
except ValueError: print(a == b)" "$result" "$expected") == True ]]; then
    PASS=$((PASS + 1)); printf '  PASS  %s  [%s]\n' "$description" "$result"
  else
    FAIL=$((FAIL + 1)); printf '  FAIL  %s  — wanted %s, got %s\n' "$description" "$expected" "$result"
  fi
}

sql_true() {
  local description=$1 query=$2 result
  result=$(sql "$query")
  if [[ $result == t ]]; then
    PASS=$((PASS + 1)); printf '  PASS  %s\n' "$description"
  else
    FAIL=$((FAIL + 1)); printf '  FAIL  %s  — query returned "%s"\n' "$description" "$result"
  fi
}

login() {
  call POST /auth/login "" "{\"username\":\"$1\",\"password\":\"$PASSWORD\"}"
  [[ $STATUS == 200 ]] || { echo "Login failed for $1: $STATUS $RESPONSE" >&2; exit 2; }
  field 'd["token"]'
}

balance_of() {
  sql "select coalesce(sum(case when p.direction = 'CREDIT' then p.amount else -p.amount end), 0)
       from ledger.postings p
       join ledger.accounts a on a.account_id = p.account_id
       where a.account_number = '$1'"
}

expect_balance() {
  local description=$1 account=$2 expected=$3 actual
  actual=$(balance_of "$account")
  if [[ $(python3 -c "print(float('$actual') == float('$expected'))") == True ]]; then
    PASS=$((PASS + 1)); printf '  PASS  %s  [%s]\n' "$description" "$actual"
  else
    FAIL=$((FAIL + 1)); printf '  FAIL  %s  — wanted %s, got %s\n' "$description" "$expected" "$actual"
  fi
}

# slip_json RECEIVER_ID PAYER_ACCOUNT PAYEE_ACCOUNT EARNINGS DEDUCTIONS TITLE
slip_json() {
  cat <<JSON
{
  "receiverId": $1,
  "title": "$6",
  "organizationName": "Alpha Industries",
  "organizationAddress": {
    "streetName": "Samora Avenue", "buildingNumber": "14", "postCode": "11101",
    "townName": "Dar es Salaam", "country": "TZ"
  },
  "date": "$(date -u +%F)",
  "employeeName": "Staffer",
  "payPeriod": "September 2026",
  "designation": "Engineer",
  "workedDays": 22,
  "department": "Engineering",
  "payerAccount": "$2",
  "payeeAccount": "$3",
  "beneficiaryBank": "beta-bank",
  "earnings": [{"label": "Basic", "amount": $4}],
  "deductions": [{"label": "Tax", "amount": $5}],
  "amountInWords": "Four hundred and fifty thousand"
}
JSON
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
ALPHA_ID=$(field 'd["userId"]')
call POST /admin/institutions "$CB" "{\"username\":\"beta-bank\",\"password\":\"$PASSWORD\",\"institutionCode\":\"BETA\"}"
BETA_ID=$(field 'd["userId"]')
check "License the employer's bank and the employee's bank" 201

ALPHA=$(login alpha-bank)
BETA=$(login beta-bank)

call POST /institution/customers "$ALPHA" "{\"username\":\"employer\",\"password\":\"$PASSWORD\"}"
EMPLOYER_ACCOUNT=$(field 'd["accountNumber"]')
EMPLOYER_ID=$(field 'd["userId"]')
call POST /institution/customers "$ALPHA" "{\"username\":\"alpha-other\",\"password\":\"$PASSWORD\"}"
ALPHA_OTHER_ACCOUNT=$(field 'd["accountNumber"]')
call POST /institution/customers "$BETA" "{\"username\":\"staffer\",\"password\":\"$PASSWORD\"}"
STAFFER_ACCOUNT=$(field 'd["accountNumber"]')
check "Open the employer's and the employee's accounts" 201

EMPLOYER=$(login employer)
call POST "/institution/customers/$EMPLOYER_ID/deposits" "$ALPHA" '{"amount":1000000,"description":"Payroll float"}'
check "Fund the employer with 1,000,000" 201

echo "== A slip naming accounts that cannot be paid is refused before it is ever sent"
call POST /slips/send "$ALPHA" "$(slip_json "$BETA_ID" "$EMPLOYER_ACCOUNT" "9999999999999999" 500000 50000 "Bad payee")"
check "Payee account that does not exist" 400 "is not a customer account at"
call POST /slips/send "$ALPHA" "$(slip_json "$BETA_ID" "$EMPLOYER_ACCOUNT" "$ALPHA_OTHER_ACCOUNT" 500000 50000 "Wrong bank")"
check "Payee held at the sending bank rather than the receiving one" 400 "is not a customer account at"
call POST /slips/send "$ALPHA" "$(slip_json "$BETA_ID" "$STAFFER_ACCOUNT" "$STAFFER_ACCOUNT" 500000 50000 "Not our payer")"
check "Payer that is not the sending bank's own customer" 400 "is not a customer account at"
sql_equals "…and none of them left a transfer behind" \
  "select count(*) from filetransfer.file_transfers" 0

echo "== Sending a slip moves no money"
call POST /slips/send "$ALPHA" "$(slip_json "$BETA_ID" "$EMPLOYER_ACCOUNT" "$STAFFER_ACCOUNT" 500000 50000 "September payroll")"
check "Alpha sends the payslip to Beta" 201
TRANSFER_ID=$(field 'd["transferId"]')
assert "…and it is not disbursed yet" 'd["paymentId"] is None and d["status"] == "SENT"'
expect_balance "The employer still holds the full float" "$EMPLOYER_ACCOUNT" 1000000
expect_balance "The employee has nothing yet" "$STAFFER_ACCOUNT" 0
sql_equals "…and no payment exists at all" "select count(*) from payments.payments" 0

echo "== Rejecting moves no money either"
call POST /slips/send "$ALPHA" "$(slip_json "$BETA_ID" "$EMPLOYER_ACCOUNT" "$STAFFER_ACCOUNT" 100000 0 "To be rejected")"
REJECTED_ID=$(field 'd["transferId"]')
check "Alpha sends a second slip" 201
call POST "/files/$REJECTED_ID/reject" "$BETA" '{"reason":"Wrong pay period"}'
check "Beta rejects it" 200
assert "…leaving it undisbursed" 'd["paymentId"] is None and d["status"] == "REJECTED"'
expect_balance "The employer is untouched by the rejection" "$EMPLOYER_ACCOUNT" 1000000

echo "== Approving disburses exactly what the signed instruction says"
call POST "/files/$TRANSFER_ID/approve" "$BETA"
check "Beta approves the payroll slip" 200
assert "…reporting the payment it made" 'd["paymentId"] is not None and float(d["disbursedAmount"]) == 450000'
expect_balance "The employer paid the net pay, not the gross" "$EMPLOYER_ACCOUNT" 550000
expect_balance "The employee was credited the net pay" "$STAFFER_ACCOUNT" 450000

DISBURSED_REF=$(sql "select p.transaction_ref from payments.payments p
                     join filetransfer.file_transfers f on f.payment_id = p.payment_id
                     where f.transfer_id = $TRANSFER_ID")
sql_equals "The transfer is linked to the payment that carried it" \
  "select count(*) from filetransfer.file_transfers where transfer_id = $TRANSFER_ID and payment_id is not null" 1
sql_equals "…which crossed banks, so it wrote four postings" \
  "select count(*) from ledger.postings where transaction_ref = '$DISBURSED_REF'" 4
sql_equals "…and produced an interbank pacs.008" \
  "select count(*) from payments.settlement_messages where uetr = '$DISBURSED_REF'" 1
sql_equals "Alpha now owes the system the net pay" \
  "select coalesce(sum(case when p.direction='CREDIT' then p.amount else -p.amount end),0)
   from ledger.postings p join ledger.accounts a on a.account_id=p.account_id
   join identity.users i on i.user_id=a.institution_id
   where a.account_type='SETTLEMENT' and i.institution_code='ALPHA'" -450000

echo "== It can only happen once"
call POST "/files/$TRANSFER_ID/approve" "$BETA"
check "Approving an already-approved slip is refused" 400 "already been reviewed"
expect_balance "…and pays nobody a second time" "$STAFFER_ACCOUNT" 450000

echo "== A slip that cannot be paid fails the approval, not the ledger"
call POST /slips/send "$ALPHA" "$(slip_json "$BETA_ID" "$EMPLOYER_ACCOUNT" "$STAFFER_ACCOUNT" 900000 0 "Too large")"
BROKE_ID=$(field 'd["transferId"]')
check "Alpha sends a slip for more than it holds" 201
call POST "/files/$BROKE_ID/approve" "$BETA"
check "Beta's approval is refused for insufficient funds" 400 "Insufficient funds"
sql_equals "…and the transfer is left awaiting review, not approved" \
  "select status from filetransfer.file_transfers where transfer_id = $BROKE_ID" "SENT"
expect_balance "…with no money moved" "$EMPLOYER_ACCOUNT" 550000
sql_equals "…though the refusal is recorded for the paying bank to see" \
  "select count(*) from payments.payments where status = 'FAILED' and failure_reason = 'Insufficient funds'" 1

echo "== A plain file carries no instruction and disburses nothing"
echo "just a document" > "$WORK_DIR/plain.txt"
STATUS=$(curl -s -o "$WORK_DIR/body" -w '%{http_code}' -X POST "$API/files/send" \
  -H "Authorization: Bearer $ALPHA" -F "file=@$WORK_DIR/plain.txt" -F "receiverId=$BETA_ID")
RESPONSE=$(cat "$WORK_DIR/body")
check "Alpha uploads a plain file" 201
PLAIN_ID=$(field 'd["transferId"]')
call POST "/files/$PLAIN_ID/approve" "$BETA"
check "Beta approves it" 200
assert "…and nothing is disbursed" 'd["paymentId"] is None'

echo "== The ledger is still sound"
# Stronger than it used to be. This once read "every posting effect sums to the total
# deposited", because a deposit wrote a single credit and money entered the ledger from
# nowhere. Deposits are now funded from the institution's till, so every movement in the
# system has two sides and the whole ledger nets to zero.
sql_true "I1  every posting in the ledger nets to zero" \
  "select coalesce(sum(case when direction = 'CREDIT' then amount else -amount end), 0) = 0
   from ledger.postings"

sql_true "I1b tills hold the negative of everything deposited" \
  "select (select coalesce(sum(case when p.direction = 'CREDIT' then p.amount else -p.amount end), 0)
           from ledger.postings p
           join ledger.accounts a on a.account_id = p.account_id
           where a.account_type = 'CASH')
        = -(select coalesce(sum(amount), 0) from payments.deposits)"
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
sql_true "I4  every payment's postings net to zero" \
  "select not exists (
     select 1 from payments.payments pay
     join ledger.postings p on p.transaction_ref = pay.transaction_ref
     where pay.transaction_ref is not null
     group by pay.transaction_ref
     having sum(case when p.direction = 'CREDIT' then p.amount else -p.amount end) <> 0)"
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

echo
echo "Passed: $PASS   Failed: $FAIL"
[[ $FAIL -eq 0 ]]
