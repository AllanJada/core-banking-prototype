# What's Left to Complete This Core Banking System

Status: a gap analysis of the system as it stands (two-tier banking, all three phases, plus the
ISO 20022 `pacs.008` interbank message).

`README.md` and `FLOWS.md` describe what the system does. This document is the other half:
what it does **not** do, what is actually **wrong** today, and what "finished" would mean.
Every item says where it would go in the code and roughly what it costs.

---

## 1. Three different bars for "complete"

It is worth separating them, because they need different work and have different urgency:

| Bar | Question it answers | Where this system stands |
|---|---|---|
| **Correct** | Does it do what it already claims, under load and abuse? | §2 — a handful of real defects, two of which can lose money |
| **Complete as banking** | Does it have the capabilities a core banking system is expected to have? | §3–§4 — substantial gaps: no reversals, no interest or fees, no account lifecycle, cards move no money |
| **Production-ready** | Could it be operated, observed and recovered by someone who didn't write it? | §5–§6 — essentially unstarted: no tests in the repo, no CI, no container, no metrics |

§2 is the only section that is urgent. The rest is scope.

---

## 2. Correctness gaps — things that are wrong today

These are ordered by what they cost if they bite.

### 2.1 A customer can be overdrawn by concurrent payments

`PaymentService.pay` reads the payer's balance with `accountService.balanceOf(...)` and no lock.
Two payments submitted at the same instant both read the pre-payment balance, both pass the
sufficient-funds check, and both post.

**Failure:** balance 100,000; two simultaneous payments of 80,000 each; both succeed; the
account ends at −60,000, which the ledger permits because nothing forbids a negative sum. The
"no overdraft, ever" promise in the README is broken, and invariant I3 still holds, so nothing
detects it.

**Fix:** the same pattern already used one level up — the paying bank's settlement account is
locked before its position is read (`findSettlementForUpdate`, `PESSIMISTIC_WRITE`). The payer's
own account needs the same treatment before the balance check. Note the daily cap
(`sumCompletedSince`) has the identical race.

*Size: small. This is the most important item in the document.*

### 2.2 A retried payment pays twice

No endpoint takes an idempotency key. A client that times out and retries `POST /payments`
produces two payments, two sets of postings and — for an inter-bank payment — two `pacs.008`
messages. Mobile clients on poor connections retry constantly; this is not a hypothetical.

**Fix:** an `Idempotency-Key` header, a table keyed on `(caller, key)` storing the first
response, and a unique constraint doing the real enforcement. Applies to `/payments`,
`/payments/deposits`, `/payment-links/{id}/pay` and card issuance.

*Size: medium, and it touches every money-moving endpoint, so it is cheaper now than later.*

### 2.3 Anyone can create money

`POST /payments/deposits` lets a customer credit their own account by any amount, unsigned by
anyone but themselves and unbounded. Every balance in the system traces back to this.

That is fine for a demonstration and fatal for anything else: deposits should originate from a
teller, a cash-in device, or an inbound interbank credit — never from the account holder's own
session. Invariants I1 and I3 are stated in terms of "total deposited", so they will happily
confirm a ledger built on invented money.

**Fix:** move deposits behind the institution (a teller operation on
`InstitutionController`), or model them as inbound settlement. Keep the customer-facing route
only under a clearly named demo flag.

*Size: small mechanically, but it changes the system's story, so decide deliberately.*

### 2.4 Everything is a 400

`GlobalExceptionHandler` maps `RuntimeException` to 400, so "Customer not found", "insufficient
funds" and "amount must be positive" are indistinguishable to a client except by matching
English prose. There are no error codes.

**Fix:** a small exception hierarchy (`NotFoundException` → 404, `ConflictException` → 409,
validation → 422) and a stable `code` field in `ApiError`. The tenancy design deliberately
returns "not found" for another bank's customer — that intent survives a 404 fine.

*Size: small, but it is an API contract change, so it wants doing before anyone integrates.*

### 2.5 The debit card authorises nothing

`DebitCard`, its PIN, the Luhn-valid number, the three-strikes lock and the unblock flow all
exist — and no payment path references any of it (verified: `PaymentService` and
`PaymentLinkService` contain no card code at all). The card is an object a customer owns and
cannot spend with.

**Fix:** either a card-present/card-not-present authorisation path that verifies the PIN and
then moves money through the same ledger primitives, or an honest note in the README that cards
are identity artefacts only. The first is the interesting one, since it is where an
authorisation hold (reserved-but-not-posted funds) would first be needed.

*Size: medium for a real authorisation flow.*

---

## 3. Missing banking capability

### 3.1 No reversals, refunds or corrections
An append-only ledger needs a compensating-entry flow — and needs it to be the *only* way a
mistake is undone. Today there is no way to reverse a payment at all. Wants: a `reversal_of`
reference on the payment, postings that mirror the original under a new `transactionRef`, and a
rule that a reversal can never itself be reversed twice.

### 3.2 No account lifecycle
An account is opened and then exists forever. Missing: close, freeze/block (only *cards* can be
blocked), dormancy, and reopening. `Account` has no status column at all.

### 3.3 One account per customer, one product
`AccountRepository.findByOwner_UserId` returns `Optional` — one account per owner is baked in.
No current/savings/loan distinction, no product catalogue, no per-product rules.

### 3.4 No interest, fees or charges
No accrual, no posting schedule, no fee on a payment, no charge on a card. `pacs.008` already
carries `ChrgBr: SLEV` — "no charges levied" — which is currently true and would stop being so.

### 3.5 No scheduled or recurring payments
No standing orders, no direct debits, no future-dated transfers. Wants a scheduler and a
durable job table; note the existing design has no background processing at all, which is
partly why `EXPIRED` states are derived on read rather than swept.

### 3.6 Single currency
`Account.currency` exists and every account gets the configured default. No FX rate source, no
cross-currency payment, no revaluation. The `pacs.008` mapping already has the currency fields
that would carry it.

### 3.7 Settlement is only half a settlement system
Positions move solely through payments. Missing: **reserve funding** (the Central Bank crediting
a bank's position), **netting and settlement runs** (positions currently never return to zero),
per-institution cap overrides (one configured cap applies to every bank), and any notion of a
business day or cut-off.

### 3.8 ~~Payroll goes nowhere~~ — done
A slip's `pain.001` now moves money. The receiving institution's **approval** executes the
instruction: the amount and both accounts are read out of the signed payload *after* its
integrity is re-verified, so what moves is exactly what was signed. Sending moves nothing and
rejecting moves nothing, which is what keeps rejection cheap in a system with no reversals
(§3.1). The payer must be a customer of the sending bank and the payee of the receiving one,
so the account numbers on a slip are no longer decorative. Covered by
`bank-backend/scripts/verify-slip-disbursement.sh`.

---

## 4. Interbank and standards

### 4.1 The message is generated but never sent
A `pacs.008` is written, signed, stored and made downloadable — by both banks, from the same
server. There is no transport. In a real deployment the receiving bank is a different system,
and the existing file-transfer module is the closest thing to a channel.

### 4.2 No reply messages
`pacs.008` is one of a family. Missing: **`pacs.002`** (status report — accepted/rejected),
**`pacs.004`** (payment return), **`camt.053`/`camt.052`** (statements to the institution),
**`pacs.009`** (bank-to-bank own-account transfers, which is what reserve funding in §3.7 would
actually be). `pacs.002` is the obvious next one: today nothing tells the sending bank that the
receiving bank accepted the instruction.

### 4.3 No way for an outsider to get a public key
Signatures are verifiable only by someone with database access — the phase 2 and `pacs.008`
verification scripts read keys straight from `identity.users`. A counterparty verifying a
statement or message has no endpoint to fetch the institution's public key from, which makes the
signatures less useful than they look.

**Fix:** a public `GET /institutions/{code}/key` (or a JWKS-style document), plus a `keyId` on
signed records so keys can rotate without invalidating old signatures — see §5.2.

---

## 5. Security and identity

### 5.1 Key custody (already stated in the README)
Every private key is generated and held server-side, so no signature proves anything against
this server itself. A KMS/HSM, or client-side signing, is the only real fix. This is the
limitation that `FLOWS.md` §6 discusses moving zero-knowledge proofs against.

### 5.2 No key rotation
There is no `keyId` on any signed row. Rotating an institution's key would silently invalidate
every statement and message it ever signed, because verification rebuilds the envelope and
checks it against the key the user row holds *now*.

### 5.3 Login has none of the protections cards have
Verified: `AuthService` has no attempt limiting, no lockout, no password reset, no password
policy. Three wrong PINs block a card; unlimited wrong passwords block nothing. There is also no
MFA and no way to revoke a token before it expires — JWTs are stateless with a 120-minute life
and no refresh or deny-list.

### 5.4 No audit trail of actions
Signatures cover documents, not decisions. Nothing records who licensed an institution, who
unblocked a card, or who read a customer's data. For a supervised system this is usually a hard
requirement, and the append-only posting design is a good model to copy for it.

### 5.5 Transport and abuse controls
CORS is `*`, there is no rate limiting anywhere, and no TLS guidance. Names, account numbers and
card numbers sit unencrypted in the database (files are encrypted at rest; rows are not).

---

## 6. Engineering and production readiness

### 6.1 There are no tests in the repository
Verified: the backend has exactly one test, `MldsaApplicationTests.contextLoads`, and the
frontend has none. The five verification suites are real and thorough, but they are **external**
— they need a running application, a live Postgres, and an empty database, and they must be run
in sequence with truncation between them.

**Fix:** Testcontainers-backed integration tests for the service layer (the ledger invariants,
tenancy refusals and the settlement cap are perfect candidates), plus unit tests for the pieces
with real logic and no I/O: `CryptoService` envelopes, `CardService.luhnCheckDigit`,
`StatementService` period arithmetic, `Pacs008GenerationService` mapping. The existing scripts
then become end-to-end checks rather than the only checks.

*Size: this is the single biggest engineering gap, and everything else in this section depends
on it.*

### 6.2 No CI
Verified: no `.github`. Nothing runs `mvn package`, `npm run build`, `oxlint` or the
verification suites automatically. A workflow that spins up Postgres, migrates, boots the jar
and runs all five suites would turn this document's §2 items into regressions that can't return.

### 6.3 Nothing to deploy with
No Dockerfile, no compose file, no environment profiles. Running it means Java 26, a local
Postgres, a Playwright browser download, and hand-set environment variables. A compose file
covering app + Postgres would also make the verification suites trivially runnable.

### 6.4 No observability
Verified: no Actuator, no Micrometer. No health or readiness endpoint, no metrics, no structured
logging, no tracing. Notably, **nothing alerts if invariant I2 breaks** — the Central Bank
console shows a banner if someone happens to be looking at it.

### 6.5 Frontend housekeeping
`dist/` is committed to git and drifts from source (it has repeatedly needed rebuilding during
development). The bundle is ~600 kB with no code splitting. There are no component tests, no
accessibility audit, and no internationalisation despite a Tanzanian setting.

### 6.6 Documented-but-absent operational procedures
No backup or restore runbook, no disaster recovery, no data retention policy, no key ceremony,
no runbook for "the positions don't sum to zero".

---

## 7. Deliberately out of scope

Named so their absence is a decision rather than an oversight — this list is from the plan and
the README, unchanged:

- **Regulatory**: KYC/AML, sanctions screening, suspicious transaction reporting, regulatory
  returns
- **Fraud**: velocity checks, behavioural monitoring, device fingerprinting
- **Architecture**: event-bus microservices, CQRS, separate read models
- **Post-quantum**: ML-KEM key encapsulation (the Ed25519 swap deliberately stepped back from
  ML-DSA)
- **Organisational**: institution staff users with roles, customers banking at more than one
  institution

---

## 8. A suggested order

| # | Work | Why here | Size |
|---|---|---|---|
| 1 | Lock the payer's account (§2.1) | It can lose money today | S |
| 2 | Idempotency keys (§2.2) | Same, and cheaper before more endpoints exist | M |
| 3 | Decide the deposit story (§2.3) | Everything downstream inherits it | S |
| 4 | Tests in the repo + CI (§6.1, §6.2) | Nothing above stays fixed without this | L |
| 5 | Error semantics (§2.4) | API contract, cheapest before integrators arrive | S |
| 6 | Audit trail (§5.4) | Supervised systems need it; ledger gives the model | M |
| 7 | Reversals (§3.1) | The first genuinely missing banking capability | M |
| 8 | `pacs.002` + public key endpoint (§4.2, §4.3) | Makes the interbank leg a conversation, and its signatures checkable | M |
| 9 | Reserve funding and settlement runs (§3.7) | Completes the settlement tier | L |
| 10 | Card authorisation (§2.5) | Makes the card real; introduces holds | L |
| 11 | Container + observability (§6.3, §6.4) | Needed before anyone else operates it | M |

Items 1–3 are a day's work between them and remove the defects that can actually cost money.
Item 4 is what stops this list regrowing.
