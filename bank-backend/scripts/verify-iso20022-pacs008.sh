#!/usr/bin/env bash
#
# ISO 20022 pacs.008 on the interbank leg.
#
# A pain.001 is what a customer sends their own bank to initiate a transfer; a pacs.008 is
# what that bank then sends the receiving bank to settle it. This checks that every payment
# crossing institutions writes one, that a payment inside a single bank writes none, that the
# message says what the ledger did, and that only the two banks party to it (and the Central
# Bank, as settlement operator) can read it.
#
# The message is validated against the official schema and its signature verified *outside*
# the application — by python's lxml and cryptography, over the bytes the API served — rather
# than asking the application to confirm its own output.
#
# Needs a running backend on an EMPTY database, curl, psql, and python3 with lxml and
# cryptography.
#
#   API=http://localhost:8080/api/v1 ./scripts/verify-iso20022-pacs008.sh
#
# Exits non-zero if any check fails.

set -uo pipefail

API="${API:-http://localhost:8080/api/v1}"
PGHOST="${PGHOST:-localhost}"
PGUSER="${PGUSER:-postgres}"
PGDATABASE="${PGDATABASE:-mldsa}"
export PGPASSWORD="${PGPASSWORD:-postgres}"
SCHEMA="${SCHEMA:-src/main/resources/iso20022/pacs.008.001.08.xsd}"
STORAGE="${STORAGE:-./storage/files}"

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
  if [[ $result == "$expected" ]]; then
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

download() {  # download TOKEN PATH OUTFILE -> STATUS
  STATUS=$(curl -s -o "$3" -w '%{http_code}' -H "Authorization: Bearer $1" "$API$2")
  RESPONSE=$(head -c 400 "$3")
}

echo "== Precondition: empty database"
call GET /users/bootstrap
if [[ $STATUS != 200 || $(field 'd["open"]') != "True" ]]; then
  echo "Bootstrap is not open ($STATUS $RESPONSE). Run this against an empty database." >&2
  exit 2
fi
if [[ ! -f $SCHEMA ]]; then
  echo "Schema not found at $SCHEMA — run from bank-backend/ or set SCHEMA." >&2
  exit 2
fi

call POST /users "" "{\"username\":\"cb-overseer\",\"password\":\"$PASSWORD\",\"role\":\"BANK\"}"
check "Bootstrap the Central Bank overseer" 201
CB=$(login cb-overseer)

for bank in ALPHA BETA GAMMA; do
  lower=$(echo "$bank" | tr '[:upper:]' '[:lower:]')
  call POST /admin/institutions "$CB" "{\"username\":\"$lower-bank\",\"password\":\"$PASSWORD\",\"institutionCode\":\"$bank\"}"
done
check "License three institutions" 201

ALPHA=$(login alpha-bank)
BETA=$(login beta-bank)
GAMMA=$(login gamma-bank)

call POST /institution/customers "$ALPHA" "{\"username\":\"ann\",\"password\":\"$PASSWORD\"}"
ANN_ACCOUNT=$(field 'd["accountNumber"]')
ANN_ID=$(field 'd["userId"]')
call POST /institution/customers "$ALPHA" "{\"username\":\"amos\",\"password\":\"$PASSWORD\"}"
AMOS_ACCOUNT=$(field 'd["accountNumber"]')
call POST /institution/customers "$BETA" "{\"username\":\"ben\",\"password\":\"$PASSWORD\"}"
BEN_ACCOUNT=$(field 'd["accountNumber"]')
check "Open customers at two of them" 201

ANN=$(login ann)
call POST "/institution/customers/$ANN_ID/deposits" "$ALPHA" '{"amount":100000,"description":"Opening deposit"}'
check "Ann deposits 100,000" 201

echo "== A payment inside one bank writes no interbank message"
call POST /payments "$ANN" "{\"toAccountNumber\":\"$AMOS_ACCOUNT\",\"amount\":5000,\"description\":\"Same bank\"}"
check "Ann pays Amos, both at Alpha" 201
INTRA_PAYMENT=$(field 'd["paymentId"]')
sql_equals "…and no pacs.008 is written for it" \
  "select count(*) from payments.settlement_messages where payment_id = $INTRA_PAYMENT" 0

echo "== A payment across banks writes one"
call POST /payments "$ANN" "{\"toAccountNumber\":\"$BEN_ACCOUNT\",\"amount\":25000,\"description\":\"Invoice 42\"}"
check "Ann pays Ben at Beta" 201
INTER_PAYMENT=$(field 'd["paymentId"]')
INTER_REF=$(field 'd["transactionRef"]')
sql_equals "…and exactly one pacs.008 is written for it" \
  "select count(*) from payments.settlement_messages where payment_id = $INTER_PAYMENT" 1
sql_equals "…carrying the ledger's own reference as its UETR" \
  "select uetr from payments.settlement_messages where payment_id = $INTER_PAYMENT" "$INTER_REF"
sql_equals "…naming the two banks as debtor and creditor agent" \
  "select debtor_agent_code || '->' || creditor_agent_code from payments.settlement_messages where payment_id = $INTER_PAYMENT" \
  "ALPHA->BETA"

MESSAGE_ID=$(sql "select message_id from payments.settlement_messages where payment_id = $INTER_PAYMENT")

echo "== Who may read it"
download "$ALPHA" "/institution/settlement-messages/$MESSAGE_ID/xml" "$WORK_DIR/sent.xml"
check "The sending bank downloads it" 200
download "$BETA" "/institution/settlement-messages/$MESSAGE_ID/xml" "$WORK_DIR/received.xml"
check "The receiving bank downloads it" 200
download "$GAMMA" "/institution/settlement-messages/$MESSAGE_ID/xml" "$WORK_DIR/third.xml"
check "A bank that is not a party to it cannot" 400 "not found"
download "$ANN" "/institution/settlement-messages/$MESSAGE_ID/xml" "$WORK_DIR/customer.xml"
check "A customer cannot" 403
download "$CB" "/admin/settlement/messages/$MESSAGE_ID/xml" "$WORK_DIR/central.xml"
check "The Central Bank can, as settlement operator" 200

call GET /institution/settlement-messages "$ALPHA"
assert "Alpha lists it as SENT" 'd["content"][0]["direction"] == "SENT"'
call GET /institution/settlement-messages "$BETA"
assert "Beta lists the same message as RECEIVED" 'd["content"][0]["direction"] == "RECEIVED"'
call GET /institution/settlement-messages "$GAMMA"
assert "Gamma sees none at all" 'd["totalElements"] == 0'

echo "== The message itself, checked outside the application"
python3 - "$WORK_DIR/sent.xml" "$SCHEMA" "$INTER_REF" "$ANN_ACCOUNT" "$BEN_ACCOUNT" <<'PYTHON'
import sys
from lxml import etree

xml_path, schema_path, uetr, debtor_account, creditor_account = sys.argv[1:6]
NS = {"p": "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08"}
failures = 0

def check(description, ok, detail=""):
    global failures
    print(f"  {'PASS' if ok else 'FAIL'}  {description}" + ("" if ok or not detail else f" — {detail}"))
    if not ok:
        failures += 1

schema = etree.XMLSchema(etree.parse(schema_path))
doc = etree.parse(xml_path)

# The gate the standard exists for: a receiving bank may reject a malformed message outright.
check("Validates against the official pacs.008.001.08 schema", schema.validate(doc),
      str(schema.error_log))

def text(path):
    found = doc.getroot().findall(path, NS)
    return found[0].text if found else None

check("Root is FIToFICstmrCdtTrf in the pacs.008 namespace",
      doc.getroot().tag == "{urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08}Document"
      and doc.getroot().find("p:FIToFICstmrCdtTrf", NS) is not None)
check("Settlement method is CLRG — across the Central Bank, not either agent's books",
      text(".//p:SttlmInf/p:SttlmMtd") == "CLRG")
check("UETR is the ledger's own transaction reference",
      text(".//p:PmtId/p:UETR") == uetr, f"got {text('.//p:PmtId/p:UETR')}")

amount_el = doc.getroot().findall(".//p:CdtTrfTxInf/p:IntrBkSttlmAmt", NS)[0]
check("Interbank settlement amount and currency match the payment",
      amount_el.text == "25000.00" and amount_el.get("Ccy") == "TZS",
      f"{amount_el.text} {amount_el.get('Ccy')}")

check("Debtor is the paying customer, on their own account",
      text(".//p:CdtTrfTxInf/p:Dbtr/p:Nm") == "ann"
      and text(".//p:CdtTrfTxInf/p:DbtrAcct//p:Othr/p:Id") == debtor_account)
check("Creditor is the receiving customer, on theirs",
      text(".//p:CdtTrfTxInf/p:Cdtr/p:Nm") == "ben"
      and text(".//p:CdtTrfTxInf/p:CdtrAcct//p:Othr/p:Id") == creditor_account)
check("Agents are the two banks, by their own codes",
      text(".//p:CdtTrfTxInf/p:DbtrAgt//p:Othr/p:Id") == "ALPHA"
      and text(".//p:CdtTrfTxInf/p:CdtrAgt//p:Othr/p:Id") == "BETA")
check("No IBAN is claimed for accounts that have none",
      doc.getroot().find(".//p:IBAN", NS) is None)
check("Remittance information carries what the payment was for",
      text(".//p:RmtInf/p:Ustrd") == "Invoice 42")

sys.exit(1 if failures else 0)
PYTHON
if [[ $? == 0 ]]; then PASS=$((PASS + 9)); else FAIL=$((FAIL + 1)); fi

echo "== Both banks received byte-identical bytes"
if cmp -s "$WORK_DIR/sent.xml" "$WORK_DIR/received.xml" && cmp -s "$WORK_DIR/sent.xml" "$WORK_DIR/central.xml"; then
  PASS=$((PASS + 1)); echo "  PASS  Sender, receiver and Central Bank all get the same document"
else
  FAIL=$((FAIL + 1)); echo "  FAIL  The three downloads differ"
fi

echo "== Its signature, verified outside the application"
python3 - "$WORK_DIR/sent.xml" \
  "$(sql "select uetr || '|' || debtor_agent_code || '|' || creditor_agent_code || '|' || to_char(amount, 'FM9999999990.00') || '|' || xml_hash || '|' || floor(extract(epoch from created_at) * 1000)::bigint from payments.settlement_messages where message_id = $MESSAGE_ID")" \
  "$(sql "select signature from payments.settlement_messages where message_id = $MESSAGE_ID")" \
  "$(sql "select u.public_key from identity.users u join payments.settlement_messages m on m.debtor_agent_id = u.user_id where m.message_id = $MESSAGE_ID")" \
  "$(sql "select xml_hash from payments.settlement_messages where message_id = $MESSAGE_ID")" <<'PYTHON'
import base64, hashlib, sys
from lxml import etree
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.serialization import load_der_public_key

xml_path, envelope, signature, public_key, stored_hash = sys.argv[1:6]
failures = 0

def check(description, ok, detail=""):
    global failures
    print(f"  {'PASS' if ok else 'FAIL'}  {description}" + ("" if ok or not detail else f" — {detail}"))
    if not ok:
        failures += 1

# Exclusive canonicalisation then SHA-384, independently of the application's own code.
canonical = etree.tostring(etree.parse(xml_path), method="c14n", exclusive=True, with_comments=False)
computed = hashlib.sha384(canonical).hexdigest()
check("The stored hash is the canonical hash of the served document",
      computed == stored_hash, f"computed {computed[:16]}…, stored {stored_hash[:16]}…")

key = load_der_public_key(base64.b64decode(public_key))
try:
    key.verify(base64.b64decode(signature), envelope.encode("utf-8"))
    check("The signature verifies against the sending bank's public key", True)
except InvalidSignature:
    check("The signature verifies against the sending bank's public key", False, envelope)

try:
    key.verify(base64.b64decode(signature), envelope.replace("|25000.00|", "|99999.00|").encode("utf-8"))
    check("An altered amount no longer verifies", False)
except InvalidSignature:
    check("An altered amount no longer verifies", True)

sys.exit(1 if failures else 0)
PYTHON
if [[ $? == 0 ]]; then PASS=$((PASS + 3)); else FAIL=$((FAIL + 1)); fi

echo "== Tampering with the stored message is caught on the way out"
STORED=$(sql "select stored_filename from payments.settlement_messages where message_id = $MESSAGE_ID")
if [[ -f "$STORAGE/$STORED" ]]; then
  cp "$STORAGE/$STORED" "$WORK_DIR/original.bin"
  # Flip one byte deep inside the stored file, leaving its length untouched.
  python3 -c "
import sys
path = sys.argv[1]
data = bytearray(open(path, 'rb').read())
data[len(data) // 2] ^= 0x01
open(path, 'wb').write(data)" "$STORAGE/$STORED"
  download "$ALPHA" "/institution/settlement-messages/$MESSAGE_ID/xml" "$WORK_DIR/tampered.xml"
  if [[ $STATUS == 400 && $RESPONSE == *alter* ]]; then
    PASS=$((PASS + 1)); echo "  PASS  A tampered message is refused rather than served  [400]"
  else
    FAIL=$((FAIL + 1)); echo "  FAIL  A tampered message came back as $STATUS: $RESPONSE"
  fi
  cp "$WORK_DIR/original.bin" "$STORAGE/$STORED"
  download "$ALPHA" "/institution/settlement-messages/$MESSAGE_ID/xml" "$WORK_DIR/restored.xml"
  check "…and serves again once restored" 200
else
  echo "  SKIP  stored file not found at $STORAGE/$STORED (set STORAGE=)"
fi

echo "== The Central Bank's settlement view links movements to their instruction"
call GET /admin/settlement "$CB"
assert "Each movement carries the message that instructed it" \
  'all(m["messageId"] for m in d["movements"]["content"])'
call GET /admin/settlement/messages "$CB"
assert "…and every message is listed, both sides named" \
  'd["totalElements"] == 1 and d["content"][0]["debtorAgentCode"] == "ALPHA" and d["content"][0]["creditorAgentCode"] == "BETA"'

echo
echo "Passed: $PASS   Failed: $FAIL"
[[ $FAIL -eq 0 ]]
