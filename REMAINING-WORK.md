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
| **Correct** | Does it do what it already claims, under load and abuse? | §2 — the three defects that could lose money are fixed (§2.1–§2.3); what remains is error semantics and a card that authorises nothing |
| **Complete as banking** | Does it have the capabilities a core banking system is expected to have? | §3–§4 — substantial gaps: no reversals, no interest or fees, no account lifecycle, cards move no money |
| **Production-ready** | Could it be operated, observed and recovered by someone who didn't write it? | §5–§6 — barely started: a compose stack exists (§6.3), but still no tests in the repo, no CI and no metrics |

§2 was the only urgent section, and its money-losing items are now closed — see §8 for what
that leaves. The rest is scope, with the exception of §6.1: without tests in the repository,
nothing already fixed is guaranteed to stay fixed.

---

## 2. Correctness gaps — things that are wrong today

These are ordered by what they cost if they bite.

### 2.1 A customer can be overdrawn by concurrent payments — **done** (`40de835`)

`PaymentService.execute` read the payer's balance with `accountService.balanceOf(...)` and no
lock. Two payments submitted at the same instant both read the pre-payment balance, both passed
the sufficient-funds check, and both posted.

**Reproduced before it was fixed**, as a check in `verify-two-tier-phase3.sh`: an account funded
with exactly 300,000, two payments of 200,000 fired together. Both returned `201` and the
account ended at −100,000 — while I1, I2 and I3 all still passed. That is the part worth
remembering: the invariant suite structurally cannot see this class of fault, so it needed a
test of its own rather than a stronger invariant.

**Done:** the payer's row is taken before anything that reads it. The daily-cap total
(`sumCompletedSince`) is read under the same lock, so its identical race closed with the same
line. Every path that moves money — a customer payment, a payment link, a payroll disbursement —
arrives at `execute()`, so none of them can skip it.

**The part that was not obvious.** The fix this entry originally proposed — reuse
`PESSIMISTIC_WRITE`, as the settlement account does — would have deadlocked every rejection.
JPA's pessimistic write is Postgres `FOR UPDATE`; inserting a row with a foreign key to a locked
row needs `FOR KEY SHARE` on it; the two conflict. `FailedPaymentRecorder` writes the FAILED
payment in its own `REQUIRES_NEW` transaction, and that record has a foreign key to the payer's
account — so the inner transaction would have blocked on a lock the outer one could not release
until the inner returned. A refused payment would have hung instead of being refused.

`FOR NO KEY UPDATE` is the weaker lock for exactly this case: it still serialises writers, so
the race stays closed, but it does not conflict with a foreign key reference. Both behaviours
were checked directly against Postgres before choosing, and the suite proves it end to end — the
losing payment returns `400 Insufficient funds` rather than hanging. The existing settlement lock
is unaffected, because a failed-payment row never references a settlement account.

### 2.2 A retried payment pays twice — **done** (`b0b901a`)

No endpoint took an idempotency key. A client that timed out and retried `POST /payments`
produced two payments, two sets of postings and — for an inter-bank payment — two `pacs.008`
messages. Mobile clients on poor connections retry constantly; this was never hypothetical.

**Done:** an `Idempotency-Key` header, a `payments.idempotency_keys` table keyed on
`(caller, key)`, and a unique index doing the real enforcement — claiming is an insert that
either succeeds or violates it, so two duplicates arriving together cannot both find the key
unclaimed. Reading first and then inserting would have had precisely the race §2.1 was about.
Implemented as `IdempotencyFilter` rather than four controller checks, so the guarantee cannot
differ between endpoints.

The header is **optional**: a request without one behaves exactly as before, so clients that
have not adopted it are unaffected.

Three decisions worth recording, because they are the ones a reader would otherwise have to
reverse-engineer:

- The claim is committed **before** the guarded request runs, in its own `REQUIRES_NEW`
  transaction. Joining the caller's transaction would leave it invisible until the payment had
  already been made, and would roll it back whenever the request failed.
- A key reused with a **different body** is refused with `422` rather than answered. Replaying
  the first response would confirm a payment that was never made.
- A key is **released** when its request did not succeed. A refusal moved no money, so the client
  may retry once the reason is gone; holding the key would turn a temporary refusal into a
  permanent one. Successes are never released.

Guards `/payments`, `/institution/customers/{id}/deposits`, `/payment-links/{id}/pay` and card
issuance. Slip composition and file upload are excluded deliberately — two slips are meant to be
distinguishable, and double approval is already refused by the unique `payment_id` constraint
from V5.

`verify-idempotency.sh`, 31 checks: a repeated deposit leaves one deposit row and debits the till
once; a repeated payment pays once; a reused key with a different body is refused; two duplicates
racing produce exactly one payment; a refused request gives its key back; keys are scoped per
caller; requests without a key are unaffected.

**Still open:** nothing sweeps old key rows. They are small and only accumulate for requests that
carried one, but deleting a key makes the request it guarded repeatable again, so a retention
policy wants deciding rather than defaulting.

### 2.3 Anyone can create money — **done** (`a7a1f4d`)

`POST /payments/deposits` let a customer credit their own account by any amount, unsigned by
anyone but themselves and unbounded. Every balance in the system traced back to this.

Two separate faults, and the second was the worse one. The customer was the wrong actor — but
underneath that, `AccountService.credit()` wrote a *single* credit posting with no
counterparty. A single-entry operation in a double-entry ledger: money appeared, and the
ledger's central claim held everywhere except at the point all the money came from. Moving the
button to a teller alone would have made money creation attributable without making it
balanced.

**Done:**

- Deposits moved to `POST /institution/customers/{id}/deposits`, resolved within the signed-in
  institution, so another bank's customer is not found rather than credited. The customer-facing
  route is gone, not flagged.
- A new `AccountType.CASH` — the institution's till, opened with its settlement account at
  licensing. A deposit is now `transfer(till → customer)`: two postings, one `transactionRef`.
- `credit()` deleted rather than left unused, so no future caller can create money with it.
- A latent bug this surfaced: `AccountService.open()` was idempotent on **owner alone**, so an
  institution opening a second account type would have been handed back its settlement account
  — and deposits would have been funded from it. Now keyed on `(owner, type)`, with a unique
  index enforcing it rather than the application merely intending it.
- Deposits are signed by the **institution** now (`buildTellerDepositEnvelope`, which names the
  institution code), not by the beneficiary attesting to money they had not paid in.
- `V6__teller_deposits.sql` backfills tills for existing institutions and writes the missing
  debit side of every historical deposit, dated to the original deposit rather than to the
  migration.

Invariants got stronger rather than being reworded around the problem:

| | Before | Now |
|---|---|---|
| I1 | every posting effect sums to the total deposited | **every posting in the ledger nets to zero** |
| I1b | — | tills hold the negative of everything deposited |
| I4b | every deposit's postings net to its amount | every deposit's postings net to **zero** |

Verified against a real database: on the demo data the ledger-wide sum went from 1,000,000 to
**0.00**, and CRDB's till reads −1,000,000 — the money it has put into circulation, now a
number on an account instead of an absence of one.

**Still open:** per-teller limits, a four-eyes threshold for large deposits (the payslip
approve/disburse split is the pattern), and where the till's own money comes from — the Central
Bank issuing it is the natural next step and completes the three-tier story.

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
frontend has none. The six verification suites are real and thorough, but they are **external**
— they need a running application, a live Postgres, and an empty database each. `run-suite.sh`
now automates that sequence, which removes the footgun but not the dependency: none of these
checks can run without a live system, so none of them run during a build.

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
and runs all six suites would turn this document's §2 items into regressions that can't return.
`scripts/run-suite.sh` is the per-suite half of that already written, so a workflow is mostly a
matter of looping it.

### 6.3 Nothing to deploy with — **mostly done** (`d8eeb8d`, `799b9a6`)
There is now a `docker-compose.yml` covering Postgres, the backend and the frontend, with a
Dockerfile for each. The backend image is built on Playwright's own, pinned to the client version
in `pom.xml`, so the headless Chromium that renders statements and payslips is present and
matched. Secrets come from `.env`, which compose refuses to start without, rather than from the
DEV ONLY values in `application.properties`.

**Still open:** no environment profiles — there is one `application.properties` and everything is
overridden by environment variable. And the compose file has not been run end to end here: the
network it was written on blocks outbound UDP/53 from containers, so the image builds could not
be completed. It is unproven rather than known-good.

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

**Done so far**, in the order they were taken:

| Work | Commit |
|---|---|
| ~~Decide the deposit story (§2.3)~~ | `a7a1f4d` |
| ~~Lock the payer's account (§2.1)~~ | `40de835` |
| ~~Idempotency keys (§2.2)~~ | `b0b901a` |
| ~~Container (§6.3, part)~~ | `d8eeb8d` |

That is every defect in this document that could actually lose money. What is left is scope
rather than repair, with one exception — item 1 below, which is what stops this list regrowing.

**Next:**

| # | Work | Why here | Size |
|---|---|---|---|
| 1 | Tests in the repo + CI (§6.1, §6.2) | Nothing above stays fixed without this, and there are now six external suites to run | L |
| 2 | Error semantics (§2.4) | API contract, cheapest before integrators arrive | S |
| 3 | Audit trail (§5.4) | Supervised systems need it; ledger gives the model | M |
| 4 | Reversals (§3.1) | The first genuinely missing banking capability | M |
| 5 | `pacs.002` + public key endpoint (§4.2, §4.3) | Makes the interbank leg a conversation, and its signatures checkable | M |
| 6 | Central Bank issuance (§2.3, still open) | Completes the three-tier story: today each bank's till simply runs negative | M |
| 7 | Reserve funding and settlement runs (§3.7) | Completes the settlement tier | L |
| 8 | Card authorisation (§2.5) | Makes the card real; introduces holds | L |
| 9 | Observability (§6.4) | Needed before anyone else operates it | M |

Item 1 is now the clear priority, and the last three items made the case for it: each was
verified by spinning up a database and a backend by hand, in the right order, six times over.
`scripts/run-suite.sh` automates that sequence, but it is a workaround for the absence of tests
that can run without a live system, not a substitute for them.
