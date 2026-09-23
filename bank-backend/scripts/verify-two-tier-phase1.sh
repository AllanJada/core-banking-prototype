#!/usr/bin/env bash
#
# Two-tier banking, Phase 1: the tenant-scoping negative tests from TWO_TIER_BANKING_PLAN.md
# §10, run against a live backend rather than inferred from reading the code.
#
# Needs a running backend on an EMPTY database — it bootstraps the first Central Bank
# overseer itself, then builds the hierarchy through the provisioning chain (overseer →
# two institutions → customers) and attempts each thing the plan says must be refused.
#
#   API=http://localhost:8080/api/v1 ./scripts/verify-two-tier-phase1.sh
#
# Requires curl and python3. Exits non-zero if any check fails.

set -uo pipefail

API="${API:-http://localhost:8080/api/v1}"
BODY_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE"' EXIT

PASS=0
FAIL=0
STATUS=""
RESPONSE=""

# call METHOD PATH [TOKEN] [JSON_BODY] — sets STATUS and RESPONSE.
call() {
  local method=$1 path=$2 token=${3:-} body=${4:-}
  local args=(-s -o "$BODY_FILE" -w '%{http_code}' -X "$method" "$API$path")
  [[ -n $token ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n $body ]] && args+=(-H 'Content-Type: application/json' -d "$body")
  STATUS=$(curl "${args[@]}")
  RESPONSE=$(cat "$BODY_FILE")
}

# field PYTHON_EXPR — evaluates an expression over the last response, bound to `d`.
field() {
  python3 -c 'import json, sys; d = json.loads(sys.argv[2]); print(eval(sys.argv[1]))' "$1" "$RESPONSE"
}

# check DESCRIPTION EXPECTED_STATUS [EXPECTED_MESSAGE_SUBSTRING]
check() {
  local description=$1 want=$2 message=${3:-}
  if [[ $STATUS == "$want" && ( -z $message || $RESPONSE == *"$message"* ) ]]; then
    PASS=$((PASS + 1))
    printf '  PASS  %s  [%s]\n' "$description" "$STATUS"
  else
    FAIL=$((FAIL + 1))
    printf '  FAIL  %s  — wanted %s%s, got %s: %s\n' \
      "$description" "$want" "${message:+ \"$message\"}" "$STATUS" "$RESPONSE"
  fi
}

# assert DESCRIPTION PYTHON_CONDITION — a condition over the last response, bound to `d`.
assert() {
  local description=$1 condition=$2
  if [[ $(field "$condition" 2>/dev/null) == "True" ]]; then
    PASS=$((PASS + 1))
    printf '  PASS  %s\n' "$description"
  else
    FAIL=$((FAIL + 1))
    printf '  FAIL  %s  — condition %s over: %s\n' "$description" "$condition" "$RESPONSE"
  fi
}

login() {
  call POST /auth/login "" "{\"username\":\"$1\",\"password\":\"$2\"}"
  [[ $STATUS == 200 ]] || { echo "Login failed for $1: $STATUS $RESPONSE" >&2; exit 2; }
  field 'd["token"]'
}

PASSWORD='pass1234'

echo "== Precondition: empty database"
call GET /users/bootstrap
if [[ $STATUS != 200 || $(field 'd["open"]') != "True" ]]; then
  echo "Bootstrap is not open ($STATUS $RESPONSE). Run this against an empty database." >&2
  exit 2
fi

echo "== Bootstrap"
call POST /users "" "{\"username\":\"sneaky-institution\",\"password\":\"$PASSWORD\",\"role\":\"INSTITUTION\"}"
check "§10 Anonymous bootstrap requesting INSTITUTION is refused" 400 "must be a Central Bank overseer"
call GET /users/bootstrap
assert "…and it created nothing: bootstrap is still open" 'd["open"] is True'

call POST /users "" "{\"username\":\"cb-overseer\",\"password\":\"$PASSWORD\",\"role\":\"BANK\"}"
check "Anonymous bootstrap creates the first overseer" 201
call GET /users/bootstrap
assert "Bootstrap closes once an overseer exists" 'd["open"] is False'

call POST /users "" "{\"username\":\"late-overseer\",\"password\":\"$PASSWORD\",\"role\":\"BANK\"}"
check "§10 Anonymous caller creates a BANK once one exists" 403
call POST /users "" "{\"username\":\"late-customer\",\"password\":\"$PASSWORD\"}"
check "§10 Anonymous caller creates a default-role account once a BANK exists" 403
call POST /admin/institutions "" "{\"username\":\"anon-bank\",\"password\":\"$PASSWORD\",\"institutionCode\":\"ANON\"}"
check "§10 Anonymous caller creates an institution" 401
call POST /institution/customers "" "{\"username\":\"anon-customer\",\"password\":\"$PASSWORD\"}"
check "§10 Anonymous caller creates a customer" 401

CB=$(login cb-overseer "$PASSWORD")

echo "== Central Bank provisions institutions and overseers"
call POST /admin/institutions "$CB" "{\"username\":\"alpha-bank\",\"password\":\"$PASSWORD\",\"institutionCode\":\"alpha\"}"
check "BANK licenses Alpha Bank" 201
assert "…with its settlement account opened in the same call" 'len(d["settlementAccountNumber"]) == 16 and d["institutionCode"] == "ALPHA"'
ALPHA_SETTLEMENT=$(field 'd["settlementAccountNumber"]')

call POST /admin/institutions "$CB" "{\"username\":\"beta-bank\",\"password\":\"$PASSWORD\",\"institutionCode\":\"BETA\"}"
check "BANK licenses Beta Bank" 201
BETA_SETTLEMENT=$(field 'd["settlementAccountNumber"]')

call POST /admin/institutions "$CB" "{\"username\":\"alpha-again\",\"password\":\"$PASSWORD\",\"institutionCode\":\"ALPHA\"}"
check "Institution codes are unique" 400 "already exists"
call POST /admin/institutions "$CB" "{\"username\":\"bad-code\",\"password\":\"$PASSWORD\",\"institutionCode\":\"A-1\"}"
check "Malformed institution code is refused" 400 "3 to 8 letters or digits"

call POST /admin/overseers "$CB" "{\"username\":\"cb-deputy\",\"password\":\"$PASSWORD\"}"
check "BANK creates another overseer" 201

call POST /institution/customers "$CB" "{\"username\":\"cb-customer\",\"password\":\"$PASSWORD\"}"
check "§10 BANK creates a customer" 403

ALPHA=$(login alpha-bank "$PASSWORD")
BETA=$(login beta-bank "$PASSWORD")

echo "== Institutions provision customers"
call POST /admin/institutions "$ALPHA" "{\"username\":\"rogue-bank\",\"password\":\"$PASSWORD\",\"institutionCode\":\"ROGUE\"}"
check "§10 Institution creates an INSTITUTION" 403
call POST /admin/overseers "$ALPHA" "{\"username\":\"rogue-overseer\",\"password\":\"$PASSWORD\"}"
check "§10 Institution creates a BANK via the overseer endpoint" 403
call POST /users "$ALPHA" "{\"username\":\"rogue-bootstrap\",\"password\":\"$PASSWORD\",\"role\":\"BANK\"}"
check "§10 Institution creates a BANK via bootstrap" 403

call POST /institution/customers "$ALPHA" "{\"username\":\"ann\",\"password\":\"$PASSWORD\"}"
check "Alpha creates customer Ann" 201
ANN_ID=$(field 'd["userId"]')
call POST /institution/customers "$ALPHA" "{\"username\":\"amos\",\"password\":\"$PASSWORD\"}"
check "Alpha creates customer Amos" 201
AMOS_ACCOUNT=$(field 'd["accountNumber"]')
call POST /institution/customers "$BETA" "{\"username\":\"ben\",\"password\":\"$PASSWORD\"}"
check "Beta creates customer Ben" 201
BEN_ID=$(field 'd["userId"]')

echo "== Tenant scoping"
call GET "/institution/customers/$ANN_ID" "$ALPHA"
check "Alpha reads its own customer" 200
call GET "/institution/customers/$BEN_ID" "$ALPHA"
check "§10 Alpha reads a Beta customer by ID" 400 "Customer not found"
CROSS_TENANT="$STATUS $(field 'd["message"]')"
call GET "/institution/customers/99999999" "$ALPHA"
UNKNOWN_ID="$STATUS $(field 'd["message"]')"
if [[ $CROSS_TENANT == "$UNKNOWN_ID" ]]; then
  PASS=$((PASS + 1)); echo "  PASS  …indistinguishable from an ID that was never issued  [$UNKNOWN_ID]"
else
  FAIL=$((FAIL + 1)); echo "  FAIL  cross-tenant [$CROSS_TENANT] differs from never-issued ID [$UNKNOWN_ID]"
fi

call GET /institution/customers "$ALPHA"
check "Alpha lists customers" 200
assert "§10 …and gets only Alpha's" 'sorted(c["username"] for c in d["content"]) == ["amos", "ann"] and d["totalElements"] == 2'

ANN=$(login ann "$PASSWORD")
BEN=$(login ben "$PASSWORD")

call GET /institution/customers "$ANN"
check "A customer cannot use the institution API" 403
call GET /users/counterparties "$ANN"
check "A customer cannot list other users as counterparties" 403

echo "== Card unblocking"
call POST /cards "$BEN" '{"pin":"1234"}'
check "Ben issues a card" 201
for attempt in 1 2 3; do
  call POST /cards/me/verify-pin "$BEN" '{"pin":"0000"}'
done
check "Three wrong PINs block Ben's card" 400 "now blocked"

call POST "/institution/customers/$BEN_ID/card/unblock" "$ALPHA"
check "§10 Alpha unblocks a Beta customer's card" 400 "Customer not found"
call GET "/institution/customers/$BEN_ID" "$BETA"
assert "…and the card is still blocked" 'd["cardStatus"] == "BLOCKED"'
call POST "/institution/customers/$BEN_ID/card/unblock" "$BETA"
check "Beta unblocks its own customer's card" 200
assert "…which is active again" 'd["cardStatus"] == "ACTIVE"'
call POST /cards/me/verify-pin "$BEN" '{"pin":"1234"}'
check "…and accepts the right PIN" 204

echo "== Settlement accounts are not payable"
call POST "/institution/customers/$ANN_ID/deposits" "$ALPHA" '{"amount":100000,"description":"Opening deposit"}'
check "Ann deposits 100,000" 201
call POST /payments "$ANN" "{\"toAccountNumber\":\"$ALPHA_SETTLEMENT\",\"amount\":1000,\"description\":\"Pay own bank's settlement\"}"
check "§10 Customer pays their own bank's settlement account" 400 "No account exists with that number"
call POST /payments "$ANN" "{\"toAccountNumber\":\"$BETA_SETTLEMENT\",\"amount\":1000,\"description\":\"Pay other bank's settlement\"}"
check "§10 Customer pays another bank's settlement account" 400 "No account exists with that number"
call POST /payments "$ANN" "{\"toAccountNumber\":\"$AMOS_ACCOUNT\",\"amount\":5000,\"description\":\"Lunch\"}"
check "Customer-to-customer payment still works" 201
call GET /accounts/me "$ANN"
assert "Ann's balance reflects only the deposit and the real payment" 'float(d["balance"]) == 95000'

echo "== Central Bank sees aggregates only"
call GET /admin/institutions "$CB"
check "BANK lists institutions" 200
assert "Alpha: 2 customers holding 100,000, settlement position 0" \
  'any(i["institutionCode"] == "ALPHA" and i["customerCount"] == 2 and float(i["customerFundsHeld"]) == 100000 and float(i["settlementPosition"]) == 0 for i in d["content"])'
assert "Beta: 1 customer holding 0, settlement position 0" \
  'any(i["institutionCode"] == "BETA" and i["customerCount"] == 1 and float(i["customerFundsHeld"]) == 0 and float(i["settlementPosition"]) == 0 for i in d["content"])'
assert "…with no customer names anywhere in the response" \
  'not any(name in json.dumps(d) for name in ["\"ann\"", "\"amos\"", "\"ben\""])'
call GET /institution/customers "$CB"
check "BANK cannot list an institution's customers" 403

echo
echo "Passed: $PASS   Failed: $FAIL"
[[ $FAIL -eq 0 ]]
