#!/usr/bin/env bash
#
# Two-tier banking, Phase 2: institution-scoped banking (TWO_TIER_BANKING_PLAN.md §13).
#
# Checks the two things Phase 2 is done when: a statement verifies against the *institution's*
# public key, checked outside the application, and account and card numbers carry the issuing
# institution's bank number. (The other done-criterion — a blocked card unblocked by the
# customer's own bank and by no other — is covered by verify-two-tier-phase1.sh, which still
# passes unchanged.)
#
# The signature check is deliberately done the hard way: the statement PDF is downloaded, its
# printed values are read back out with pdftotext, the signed envelope is rebuilt from what is
# on the page, and the signature is verified by Python's cryptography library. Nothing in the
# application is asked whether its own signature is good.
#
# Needs a running backend on an EMPTY database, plus curl, pdftotext, and python3 with
# `cryptography`. Public keys are read straight from the database, since no endpoint serves
# them — in a real deployment a verifier would use the institution's published key.
#
#   API=http://localhost:8080/api/v1 ./scripts/verify-two-tier-phase2.sh
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
  local description=$1 want=$2
  if [[ $STATUS == "$want" ]]; then
    PASS=$((PASS + 1)); printf '  PASS  %s  [%s]\n' "$description" "$STATUS"
  else
    FAIL=$((FAIL + 1)); printf '  FAIL  %s  — wanted %s, got %s: %s\n' "$description" "$want" "$STATUS" "$RESPONSE"
  fi
}

# assert DESCRIPTION CONDITION [ACTUAL] — CONDITION is a shell test already evaluated to 0/1.
assert() {
  local description=$1 ok=$2 detail=${3:-}
  if [[ $ok == 0 ]]; then
    PASS=$((PASS + 1)); printf '  PASS  %s\n' "$description"
  else
    FAIL=$((FAIL + 1)); printf '  FAIL  %s%s\n' "$description" "${detail:+ — $detail}"
  fi
}

login() {
  call POST /auth/login "" "{\"username\":\"$1\",\"password\":\"$PASSWORD\"}"
  [[ $STATUS == 200 ]] || { echo "Login failed for $1: $STATUS $RESPONSE" >&2; exit 2; }
  field 'd["token"]'
}

public_key_of() {
  psql -h "$PGHOST" -U "$PGUSER" -d "$PGDATABASE" -tAc \
    "select public_key from identity.users where user_name = '$1'"
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

echo "== Institution bank numbers"
call POST /admin/institutions "$CB" "{\"username\":\"alpha-bank\",\"password\":\"$PASSWORD\",\"institutionCode\":\"ALPHA\"}"
check "License Alpha Bank" 201
ALPHA_NUMBER=$(field 'd["institutionNumber"]')
ALPHA_SETTLEMENT=$(field 'd["settlementAccountNumber"]')
[[ $ALPHA_NUMBER =~ ^[1-9][0-9]{2}$ ]]
assert "Alpha is assigned a three-digit bank number" $? "got '$ALPHA_NUMBER'"
[[ $ALPHA_SETTLEMENT == "$ALPHA_NUMBER"* ]]
assert "…which prefixes its own settlement account number" $? "$ALPHA_SETTLEMENT vs $ALPHA_NUMBER"

call POST /admin/institutions "$CB" "{\"username\":\"beta-bank\",\"password\":\"$PASSWORD\",\"institutionCode\":\"BETA\"}"
check "License Beta Bank" 201
BETA_NUMBER=$(field 'd["institutionNumber"]')
[[ $BETA_NUMBER != "$ALPHA_NUMBER" ]]
assert "Beta is assigned a different bank number" $? "both were $ALPHA_NUMBER"

ALPHA=$(login alpha-bank)
BETA=$(login beta-bank)

echo "== Account and card numbers carry the issuing bank"
call POST /institution/customers "$ALPHA" "{\"username\":\"ann\",\"password\":\"$PASSWORD\"}"
check "Alpha opens Ann's account" 201
ANN_ACCOUNT=$(field 'd["accountNumber"]')
[[ ${#ANN_ACCOUNT} == 16 && $ANN_ACCOUNT == "$ALPHA_NUMBER"* ]]
assert "Ann's 16-digit account number starts with Alpha's bank number" $? "$ANN_ACCOUNT vs $ALPHA_NUMBER"

call POST /institution/customers "$ALPHA" "{\"username\":\"amos\",\"password\":\"$PASSWORD\"}"
check "Alpha opens Amos's account" 201
AMOS_ACCOUNT=$(field 'd["accountNumber"]')

call POST /institution/customers "$BETA" "{\"username\":\"ben\",\"password\":\"$PASSWORD\"}"
check "Beta opens Ben's account" 201
BEN_ACCOUNT=$(field 'd["accountNumber"]')
[[ $BEN_ACCOUNT == "$BETA_NUMBER"* && $BEN_ACCOUNT != "$ALPHA_NUMBER"* ]]
assert "Ben's account number carries Beta's bank number, not Alpha's" $? "$BEN_ACCOUNT"

ANN=$(login ann)
call POST /cards "$ANN" '{"pin":"1234"}'
check "Ann issues a card" 201
ANN_CARD=$(field 'd["cardNumber"]')
[[ ${#ANN_CARD} == 16 && $ANN_CARD == "4$ALPHA_NUMBER"* ]]
assert "Card number is 4 + Alpha's bank number + digits" $? "$ANN_CARD"
python3 -c '
import sys
number = sys.argv[1]
digits = [int(d) for d in number][::-1]
total = sum(digits[0::2]) + sum(sum(divmod(d * 2, 10)) for d in digits[1::2])
sys.exit(0 if total % 10 == 0 else 1)' "$ANN_CARD"
assert "…and is still Luhn-valid" $? "$ANN_CARD"

echo "== The customer sees which bank holds their account"
call GET /accounts/me "$ANN"
check "Ann reads her account" 200
[[ $(field 'd["institutionName"]') == "alpha-bank" && $(field 'd["institutionCode"]') == "ALPHA" ]]
assert "…which names her institution" $? "$RESPONSE"

echo "== A statement, signed by the institution"
call POST /payments/deposits "$ANN" '{"amount":100000,"description":"Opening deposit"}'
check "Ann deposits 100,000" 201
call POST /payments "$ANN" "{\"toAccountNumber\":\"$AMOS_ACCOUNT\",\"amount\":5000,\"description\":\"Lunch\"}"
check "Ann pays Amos 5,000" 201

TODAY=$(date -u +%F)
HTTP_CODE=$(curl -s -o "$WORK_DIR/statement.pdf" -w '%{http_code}' \
  -H "Authorization: Bearer $ANN" \
  "$API/accounts/me/statement?from=$TODAY&to=$TODAY")
STATUS=$HTTP_CODE
check "Ann downloads today's statement as a PDF" 200
pdftotext -layout "$WORK_DIR/statement.pdf" "$WORK_DIR/statement.txt"
assert "The PDF is readable as text" $?

python3 - "$WORK_DIR/statement.txt" "$(public_key_of alpha-bank)" "$(public_key_of ann)" \
  "$ANN_ACCOUNT" "alpha-bank" "ALPHA" <<'PYTHON'
import base64
import re
import sys

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.serialization import load_der_public_key

text_path, institution_key, customer_key, account_number, institution_name, institution_code = sys.argv[1:7]
text = open(text_path, encoding="utf-8").read()
# The signature is one long Base64 token that wraps across lines on the page, so it is
# matched against the whitespace-stripped text.
compact = re.sub(r"\s+", "", text)

failures = 0


def check(description, ok, detail=""):
    global failures
    print(f"  {'PASS' if ok else 'FAIL'}  {description}" + ("" if ok or not detail else f" — {detail}"))
    if not ok:
        failures += 1


def verifies(key_b64, payload, signature_b64):
    key = load_der_public_key(base64.b64decode(key_b64))
    try:
        key.verify(base64.b64decode(signature_b64), payload.encode("utf-8"))
        return True
    except InvalidSignature:
        return False


check("The statement is headed with the issuing institution", institution_name in text)
check("…and prints its bank code", f"Bank code {institution_code}" in text)
check("…and says the institution signed it", f"Digitally signed by {institution_name}" in text)

signature = re.search(r"([A-Za-z0-9+/]{86}==)", compact)
generated_at = re.search(r"(\d{4}-\d{2}-\d{2}T[\d:.]+Z)", text)
period = re.search(r"(\d{2} \w{3} \d{4})\s*[–-]+\s*(\d{2} \w{3} \d{4})", text)
opening = re.search(r"Opening balance\s+([\d,]+\.\d{2})", text)
closing = re.search(r"Closing balance\s+([\d,]+\.\d{2})", text)

if not all([signature, generated_at, period, opening, closing]):
    missing = [
        name for name, match in [
            ("signature", signature), ("generated at", generated_at), ("period", period),
            ("opening balance", opening), ("closing balance", closing),
        ] if not match
    ]
    check("Every signed value can be read off the page", False, f"missing: {', '.join(missing)}")
    sys.exit(1)

check("Every signed value can be read off the page", True)

# Rebuilt exactly as CryptoService.buildStatementEnvelope joins it, from the printed values
# alone: account number | period from | period to | opening | closing | generated at.
envelope = "|".join([
    account_number,
    period.group(1),
    period.group(2),
    opening.group(1).replace(",", ""),
    closing.group(1).replace(",", ""),
    generated_at.group(1),
])

check("The signature verifies against the INSTITUTION's public key",
      verifies(institution_key, envelope, signature.group(1)))
check("…and not against the customer's own key",
      not verifies(customer_key, envelope, signature.group(1)))

tampered = envelope.replace(closing.group(1).replace(",", ""), "999999.00")
check("A statement with an altered closing balance no longer verifies",
      not verifies(institution_key, tampered, signature.group(1)))

sys.exit(1 if failures else 0)
PYTHON
PYTHON_EXIT=$?
if [[ $PYTHON_EXIT == 0 ]]; then
  # The python block prints its own PASS lines; count them once here.
  PASS=$((PASS + 7))
else
  FAIL=$((FAIL + 1))
fi

echo
echo "Passed: $PASS   Failed: $FAIL"
[[ $FAIL -eq 0 ]]
