# System Architecture

Status: reflects the implementation as it stands, not a plan. Where the code and an
earlier design document disagree, this file follows the code.

This is the technical companion to `README.md`. The README explains what each module
does and why, module by module, in the order it was built. This document instead cuts
across the whole system once, at a point in time, and shows how the pieces actually fit
together — the request path through every layer, the full data model, the security
model end to end, and the module dependency graph. Read the README for the *why* behind
individual decisions; read this for the *shape* of the whole.

---

## 1. What This System Is

A small core banking system, evolved from a file-transfer demonstration between
financial institutions and now organised as a two-tier banking system
(`TWO_TIER_BANKING_PLAN.md`, all three phases implemented). Three account roles share one
platform:

- **Normal User** — a retail banking customer of exactly one institution: an account, a
  debit card, deposits, payments, pay-by-link, statements.
- **Institution** — a commercial bank. It opens and manages its own customers (including
  unblocking their cards), holds a settlement account, and exchanges signed documents
  (payslips, with an ISO 20022 payment payload) with other institutions, subject to an
  explicit review-and-approve step on receipt.
- **Bank** — a Central Bank overseer. It supervises institutions through aggregates, and
  provisions institutions and other overseers (with one bootstrap exception, see §5.2). It
  holds no account and cannot create customers.

One Spring Boot application, one React single-page application, one PostgreSQL
database. No microservices, no message queue, no separate auth server — deliberately,
for the scale this project is at.

---

## 2. Request Path

Every authenticated API call passes through the same sequence, regardless of which
module it ends up in:

```
Browser (React SPA)
  │  fetch() with Authorization: Bearer <JWT>
  ▼
JwtAuthenticationFilter          — verifies the token, populates Spring Security's
  │                                 context with an AuthenticatedUser (userId, role)
  ▼
Spring Security route rules      — SecurityConfig's authorizeHttpRequests
  │                                 (only login and first-time setup are public)
  ▼
@PreAuthorize on the controller  — hasRole('...'), declared once per controller class
  │                                 (or per method, where a controller is mixed-access)
  ▼
Controller                       — thin: extracts @AuthenticationPrincipal, delegates,
  │                                 maps entities to response DTOs
  ▼
Service                          — all business rules live here: validation, the
  │                                 ledger's atomicity, signing, integrity checks
  ▼
Repository (Spring Data JPA)     — one per aggregate root, schema-scoped
  ▼
PostgreSQL                       — five logical schemas, one physical database
```

Two properties hold everywhere in this path, not just in one module:

- **The acting account is always the token's own subject**, resolved by
  `JwtAuthenticationFilter` into `AuthenticatedUser` and read via
  `@AuthenticationPrincipal`, never a caller-supplied `userId`. The only id a path does
  carry is an institution naming one of its customers (`/institution/customers/{id}`),
  and that id is looked up *within* the token's institution, so another bank's customer
  is simply not found (§5.4).
- **Authorization is declared at the controller, not inferred from the frontend.** Every
  controller in this system carries `@PreAuthorize("hasRole('...')")` — reachable only
  by the roles named, regardless of what UI does or doesn't offer.

---

## 3. Backend Package Layout

```
org.learning.mldsa
├── configs/          SecurityConfig — JWT filter wiring, CORS, stateless sessions,
│                     method security (@PreAuthorize) enablement
├── controllers/       One per REST resource; thin — see §2
├── dtos/              Request/response payloads, including PageResponse<T> (the
│                     shared pagination envelope) and PageRequestParams (parses and
│                     clamps ?page=&size=)
├── exceptions/        GlobalExceptionHandler — RuntimeException → 400,
│                     AccessDeniedException → 403, MaxUploadSizeExceededException → 413
├── models/            JPA entities and their enums — see §4
├── repositories/      Spring Data JPA interfaces, one per aggregate root
├── security/          JwtService (issue/parse), JwtAuthenticationFilter,
│                     AuthenticatedUser (the principal type), RestAuthenticationErrorHandler
├── services/           All business logic — see §6
├── iso20022/pain001/   JAXB classes generated at build time from the official schema
│                     (not hand-written; see §6.6)
resources/
├── iso20022/          The official pain.001.001.09 XSD — both the JAXB codegen input
│                     and the runtime validation schema, so the two can never disagree
└── templates/          Thymeleaf templates (slip.html, statement.html) rendered to
                       HTML, then to PDF by headless Chromium (Playwright)
```

## 4. Data Model

### 4.1 Schema layout

Tables are split across five Postgres schemas by owning module, inside one physical
database:

| Schema | Tables | Owning module |
|---|---|---|
| `identity` | `users` | Identity & Access |
| `ledger` | `accounts`, `postings` | Ledger |
| `payments` | `deposits`, `payments`, `payment_links`, `settlement_messages` | Payments |
| `cards` | `debit_cards` | Card Services |
| `filetransfer` | `file_transfers` | File Transfer & Review |

The schema is owned by Flyway migrations in `resources/db/migration`. The baseline,
`V1__baseline_schema.sql`, also creates the five schemas; `V2__institution_numbers.sql` adds
institutions' bank numbers, `V3__settlement_failure_detail.sql` the column holding why a bank
could not settle, `V4__settlement_messages.sql` the interbank messages table, and
`V5__slip_disbursement.sql` the link from a transfer to the payment its approval disbursed. Hibernate runs with
`ddl-auto=validate`: at startup it checks the entities against the migrated schema and
changes nothing. Flyway was adopted at the two-tier reset, after `ddl-auto=update` had
already caused a real bug (§9.2).

### 4.2 Entity relationships

```
User (identity.users)
  ├─ role: Role (NORMAL_USER | INSTITUTION | BANK)
  ├─ publicKey, privateKey: Ed25519, Base64 — both halves held server-side (§7.4)
  ├─ bic: optional ISO 9362 BIC (null for every account in this deployment)
  ├─ institutionCode: 3–8 letters/digits, unique   [INSTITUTION only]
  ├─ institutionNumber: 3 digits, unique — prefixes the account and card
  │                     numbers it issues            [INSTITUTION only]
  │
  ├──1:1── Account (ledger.accounts)   [NORMAL_USER → CUSTOMER · INSTITUTION → SETTLEMENT · BANK → none]
  │          ├─ accountNumber: 16-digit, unique
  │          ├─ type: CUSTOMER | SETTLEMENT
  │          ├─ institution → User: the customer's bank; for SETTLEMENT, the institution itself
  │          ├─ currency (single-currency deployment)
  │          ├─ (no balance column — see §4.3)
  │          │
  │          ├──1:N── Posting (ledger.postings)   [append-only, never updated]
  │          │          ├─ direction: CREDIT | DEBIT
  │          │          ├─ amount: numeric(19,2), always positive
  │          │          └─ transactionRef: ties paired postings together
  │          │
  │          ├──1:1── DebitCard (cards.debit_cards)
  │          │          ├─ cardNumber: 16-digit, Luhn-valid
  │          │          ├─ pinHash: bcrypt — no plaintext PIN, ever
  │          │          └─ failedPinAttempts, status (ACTIVE|BLOCKED), expiresOn
  │          │
  │          │
  │          │   Payment ──0..1── SettlementMessage (payments.settlement_messages)
  │          │                      one per inter-bank payment, none within a bank
  │          │                      ├─ uetr: the payment's own transactionRef
  │          │                      ├─ debtor/creditorAgent → User [INSTITUTION]
  │          │                      └─ storedFilename, xmlHash, signature
  │          │
  │          ├──1:N── Deposit (payments.deposits)
  │          ├──1:N── Payment (payments.payments)  [as fromAccount or toAccount]
  │          └──1:N── PaymentLink (payments.payment_links)  [as requesterAccount]
  │
  └──1:N── FileTransfer (filetransfer.file_transfers)   [INSTITUTION only]
             as sender ──┐
             as receiver ─┴─ same table, two FKs
             ├─ status: SENT → APPROVED|REJECTED → DOWNLOADED
             ├─ storedFilename, storedXmlFilename (payload, optional)
             ├─ fileHash, xmlHash, signature, signatureValid
             ├─ uetr: UUIDv4, minted once, never regenerated
             ├─ reviewedAt, rejectionReason (nullable — set on approve/reject)
             └─ payment → Payment (unique, nullable): the disbursement its
                approval made. Null for a plain upload, which instructs nothing
```

### 4.3 The one design decision everything else follows from

`Account` has **no balance column**. A balance is the sum of an account's postings,
computed by one aggregate query (`sumBalance`, or `sumBalancesByAccount` for every
account at once) on every read — never stored, never mutated in place.

This is why a stored counter was rejected: it can't answer "how did it reach this
number," can't be reconciled against its own history, and one bug in one operation
silently corrupts every balance that follows. A `Posting` row is append-only —
`updatable = false` on every column — so the only way to move money is to write a new
row, never to edit or delete an old one.

Every money-moving operation in the system (`AccountService.transfer`,
`AccountService.credit`, `AccountService.settleInterBank`) writes its postings inside one
`@Transactional` method, so a debit can never commit without its matching credit landing in
the same transaction.

### 4.3.1 Ledger invariants

These are executable checks, not prose — `scripts/verify-two-tier-phase3.sh` runs each as SQL
against the live database:

| ID | Invariant |
|---|---|
| I1 | Every posting in the ledger nets to exactly zero |
| I2 | Settlement positions sum to zero, always |
| I1b | Institution tills hold the negative of everything ever deposited |
| I3 | Customer balances sum to the total deposited (follows from I1 and I2) |
| I4 | Every payment's postings net to zero, and so does every deposit's |
| I5 | An intra-bank payment never writes to a settlement account |

I2 is also computed on every read of `GET /admin/summary` and `GET /admin/settlement`, and the
Central Bank console shows an error banner if it ever fails.

### 4.4 Money representation

Amounts are `numeric(19,2)`, **always positive** — a `Posting`'s `direction`
(`CREDIT`/`DEBIT`) carries the sign, not the amount's own value. This means a malformed
row can't silently reverse a transaction's meaning by having its sign misread.

Before any amount is signed, `CryptoService.canonicalAmount` renders it the one way it
is ever expressed in a signed envelope (`setScale(2, RoundingMode.UNNECESSARY)` then
`toPlainString()`), so `100.5` and `100.50` — the same money, different Java
representations — always produce the same signature.

---

## 5. Identity & Access

### 5.1 Authentication

A successful `POST /api/v1/auth/login` returns an HS256 JWT (`JwtService.issueToken`)
carrying the account's `userId` and `role` as claims, signed with a secret from
configuration (`app.jwt.secret`, `APP_JWT_SECRET` in any real deployment). No sessions,
no server-side state — `SecurityConfig` sets `SessionCreationPolicy.STATELESS`.

`JwtAuthenticationFilter` runs once per request: verifies the token (rejecting a
missing signature, a tampered payload, or an unrecognised role in one motion, via
`Jwts.parser().verifyWith(...)`), and on success populates
`SecurityContextHolder` with an `AuthenticatedUser` and one Spring Security authority —
`ROLE_<role>` — which is what `@PreAuthorize("hasRole('...')")` checks against. An
invalid or missing token is not itself rejected here; the request simply proceeds
unauthenticated, and the route/method security rules decide whether that matters (this
is what lets `/auth/login` and account bootstrap stay reachable without a token).

### 5.2 Authorization

Every controller is annotated `@PreAuthorize` at the class level, naming the one role
allowed to reach it — `FileTransferController`, `SlipController` and
`InstitutionController` are `INSTITUTION`-only,
`AccountController`/`CardController`/`PaymentController`/`PaymentLinkController` are
`NORMAL_USER`-only, `AdminController` is `BANK`-only. `UserController` is the one
mixed-access controller: `GET /users/bootstrap` and `POST /users` (bootstrap, below) are
public, while `GET /users/counterparties` is `INSTITUTION`-only at the method level.

**Provisioning follows a chain of custody.** Every account is created by the tier
directly above it, through that tier's own endpoint:

| Caller | Endpoint | Creates | In the same transaction |
|---|---|---|---|
| Anonymous, only while no `BANK` exists | `POST /users` | the first `BANK` only | — |
| `BANK` | `POST /admin/institutions` | `INSTITUTION` | its settlement account |
| `BANK` | `POST /admin/overseers` | `BANK` | — |
| `INSTITUTION` | `POST /institution/customers` | `NORMAL_USER` | the customer's account, at the calling institution |

These are separate endpoints rather than one `POST /users` with a `role` field. That way
each carries a class-level `@PreAuthorize`, instead of an authorization decision buried
in business logic.

**The bootstrap window is enforced in `UserService`**, not at the route level, because it
depends on database state. `POST /users` works without a token *only while no `BANK`
account exists yet*, and it only creates a `BANK`. A request naming any other role is
refused, so no institution can exist before a supervisor does. Once one overseer exists,
the window closes permanently.

### 5.3 What an authenticated identity carries

```java
public record AuthenticatedUser(Long userId, String username, Role role) { }
```

Nothing more. Every service method that needs to scope a query to "the caller's own
data" does so through `userId`, resolved once at the controller boundary and passed
down — never re-derived from a request parameter a client could edit.

### 5.4 Tenant scoping

Institution X must never read or change Institution Y's customers. `InstitutionService`
takes the institution from the token and puts it *inside* every lookup
(`findByOwner_UserIdAndInstitution_UserIdAndType`), rather than loading a customer and
checking ownership afterwards. A customer of another bank therefore gets the same status
and message (`Customer not found`) as an id that was never issued, which leaves nothing to
probe.

The same boundary holds in the ledger. `AccountService.requireAccountFor` returns only
`CUSTOMER` accounts, and `PaymentService` treats a settlement account number exactly like
an unknown one, so no customer operation can reach a settlement position.

`bank-backend/scripts/verify-two-tier-phase1.sh` tests every refusal in the plan's
negative-test table against a running backend on an empty database.

---

## 6. Backend Services, by Module

### 6.1 Ledger (`AccountService`)

Owns the *only* path by which a `Posting` may be written (`recordPosting`,
`transfer`, `credit` — see §4.3). Also owns opening accounts
(`openCustomerAccount` and `openSettlementAccount`, called from `UserService` inside the
same transaction as the user row, so neither a customer nor an institution can end up
with a login and no account, or vice versa) and 16-digit account number generation: the institution's
three-digit bank number followed by random digits, checked for collision and retried
up to 10 times before failing loudly rather than looping forever. The prefix is
presentation, not routing — `Account.institution` is what decides which bank holds an
account — so an institution licensed before bank numbers existed is refused outright
rather than issuing a number that would claim to belong to another bank.

### 6.2 Payments (`PaymentService`, `PaymentLinkService`, `FailedPaymentRecorder`)

`PaymentService.pay` runs every check *before* writing anything: recipient exists and is a
customer account (a settlement account is refused exactly as an unknown number is), not
paying yourself, under the per-transaction cap, sufficient balance (no overdraft — this
system will never move an account below zero), under the daily cumulative cap.

**Then it routes.** Comparing the two accounts' institutions — never anything the payer sends
— decides whether this is an intra-bank payment (two postings via `AccountService.transfer`,
settlement untouched) or an inter-bank one (four postings via
`AccountService.settleInterBank`: the payer and their bank's settlement account debited, the
payee and their bank's credited, all under one reference in one transaction).

An inter-bank payment has one extra gate: the **net debit cap**. The paying institution's
settlement account is locked (`findSettlementForUpdate`, `PESSIMISTIC_WRITE`) *before* its
position is read, so two payments leaving one bank at once cannot both be told there is room
for one of them; only the debited side is locked, so two banks paying each other cannot
deadlock. A breach is refused with a deliberately generic message to the customer
("The payment could not be settled") while the specific cause is recorded in `failureDetail`
for that institution and the Central Bank — naming a bank's liquidity to a customer would
leak it.

A passing payment is signed (`CryptoService.buildPaymentEnvelope`) and posted in one
transaction.

**Refused payments are still recorded.** `FailedPaymentRecorder` runs in its own
`REQUIRES_NEW` transaction — a separate Spring bean is required for this, since
`@Transactional` propagation is applied through a proxy and a service calling its own
method bypasses that proxy silently. Without this, the rejection's own `RuntimeException`
would roll back the very audit row meant to record it. This same pattern
(`CardPinAttemptRecorder`, below) recurs anywhere a *failure* needs to survive the
rollback it's reporting on.

`PaymentLinkService.pay` locks the link row (`PESSIMISTIC_WRITE`,
`findByLinkIdForUpdate`) for the duration of settlement, closing the race where two
people paying the identical link at the same instant could both be charged — the
loser reads `PAID` instead. Paying a link resolves to an ordinary `Payment`, so every
rule above applies unchanged.

### 6.3 Card Services (`CardService`, `CardPinAttemptRecorder`)

PIN is bcrypt-hashed (`PasswordEncoder`, the same one securing account passwords) —
nothing in the system can read a PIN back, only confirm one. **No CVV is stored at
all** (PCI DSS forbids retaining it post-authorisation, and PIN verification makes it
unnecessary). Card numbers are 16 digits with a real Luhn check digit
(`CardService.luhnCheckDigit`), opening with `4` and then the issuing institution's bank
number — where a real card carries its issuer identification — with the prefix taking the
place of random digits so the number stays 16 long and Luhn-valid. Three consecutive wrong PINs block the card — the
attempt counter, like the failed-payment recorder, commits in its own `REQUIRES_NEW`
transaction so a thrown "wrong PIN" doesn't roll back its own tally.

### 6.4 Statements (`StatementService`)

A statement is **computed, not stored** — nothing is persisted when one is generated.
Its opening balance is `sumBalanceBefore` (every posting strictly before the period),
and the closing balance is that running total after applying every posting in the
period — so two statements for adjoining periods always agree at the boundary, and
regenerating the same period twice always produces the same figures. The document is
signed (`CryptoService.buildStatementEnvelope`, over the account number, period, and
both balances) — the signature and the exact values it covers are printed on the page,
so it can be checked with nothing but the issuing institution's public key and what's on
the paper.

It is **the institution's key that signs it**, not the customer's: a statement is a document
the bank issues *about* an account, so the bank is the party attesting to the figures. The
page is headed with the institution's name and codes for the same reason — a customer's
statement should name the bank that issued it rather than imply the central bank did.

### 6.5 File Transfer & Review (`FileTransferService`)

The most involved service in the system; §7 and §8 below cover it in full detail —
signing (§7.2), at-rest encryption (§7.3), the review lifecycle (§8), and the ISO
20022 payload (§6.6, §7.5).

### 6.6 ISO 20022 (`Pain001GenerationService`)

Generates and parses `pain.001.001.09` — the real, ISO-published schema, checked into
`resources/iso20022/`. JAXB classes are code-generated from that exact file at build
time (`jaxb2-maven-plugin`), and the same file is the runtime validation schema — the
two cannot drift apart because they're compiled from the same input.

- **Generation**: `generatePain001` maps a `SlipRequest` to a `Document`, marshals it,
  then validates the marshaled bytes against the schema (`validateOrThrow`) — a payload
  that doesn't validate is never signed or sent. Institutions have no BIC in this
  deployment, so agents are identified by a proprietary scheme (`Othr/PRTRY`) rather
  than a fabricated one; accounts go through `Othr/BBAN` rather than IBAN, since
  Tanzania doesn't use it and a non-IBAN in that field would be actively misleading to
  a real receiving bank.
- **Parsing**: `parsePreview` reads a stored payload back into the handful of fields a
  human reviewer actually wants — debtor/creditor, amount, execution date, remittance
  info — for the review module (§8). Assumes the bytes are already trustworthy; the
  caller is responsible for verifying integrity first.

**`Pacs008GenerationService` and `SettlementMessageService`** are the interbank counterpart,
generating `pacs.008.001.08` from its own official XSD (a second `xjc` execution, its own
package, its own staleFile). The distinction between the two messages is the point of having
both: `pain.001` is what a customer sends their own bank to initiate a transfer, `pacs.008` is
what that bank sends the receiving bank to settle it — which this system only needed once
payments began crossing institutions.

One message is written per inter-bank payment, inside that payment's own transaction, in the
order every signed artefact here follows: generate, schema-validate, canonicalise, hash, sign
(by the sending bank), store encrypted, record. A message that cannot be generated or
validated aborts the payment rather than leaving settled money with no instruction behind it.
`SettlementMessageService.loadVerified` re-hashes and re-verifies before serving, rebuilding
the envelope from values persisted with the message rather than from the banks' current state.
Settlement method is `CLRG`: both banks settle across the Central Bank, not on either agent's
own books.

### 6.7 Supervision (`AdminService`) and Institutions (`InstitutionService`)

`AdminService` is read-only. What the `BANK` role can create (institutions and overseers)
lives in `UserService` rather than in a general admin write surface. It sees institutions
as aggregates only: `listInstitutions` returns each institution's customer count, customer
funds held and settlement position, while `settlement` returns every position with the I2
check and the bank-to-bank movements behind them — derived from completed payments whose two
institutions differ, and carrying no customer identity on either side. Each figure comes from one grouped query for the whole
page (`countPerInstitution`, `sumBalancesPerInstitution`), and none of them exposes an
individual customer's identity or balance. The earlier listing of every account was retired
for that reason.

`InstitutionService` is the institution's side. It provisions the institution's customers,
lists them with balances and card status, and unblocks a customer's card, which
`CardService` had documented as a bank operation that no role could perform. For the list,
balances and cards are fetched once per page and joined in memory. It also reports the
institution's own summary — customer aggregates, its settlement position, and the headroom
left under its net debit cap — and its own customers' payments, which is the one view where a
settlement refusal's specific cause sits beside the customer who hit it. Every method is
tenant-scoped (§5.4).

---

## 7. Cryptography, End to End

### 7.1 Signing algorithm

**Ed25519**, native to the JDK since Java 15 (JEP 339) — no external provider. This
replaced ML-DSA-65 (post-quantum, via Bouncy Castle) when the project pivoted from a
post-quantum security posture toward core banking breadth; keys and signatures shrank
from kilobytes to tens of bytes, and the `columnDefinition = "TEXT"` override the old
scheme needed became unnecessary on a fresh schema. The earlier PQC research is
preserved (not deleted) for if that priority returns.

### 7.2 What gets signed, and how the envelope has evolved

Every signed thing in this system follows the same shape: build a canonical
pipe-delimited string (an "envelope"), sign it with an Ed25519 private key, persist the
signature alongside the exact values the envelope was built from (so it can be rebuilt
identically later, never recomputed from possibly-changed data).

The signer is the party making the claim. For a transfer, a payment or a deposit that is
the acting account itself; for a **statement** it is the **institution**, since the bank is
what issues a statement about an account (§6.4).

| Envelope | Built from | Used by |
|---|---|---|
| `buildEnvelope` | senderId, receiverId, fileHash, filename, sentAt | Direct file uploads (no payload) |
| `buildCombinedEnvelope` | the above + uetr + xmlHash | Slips with an ISO 20022 payload |
| `buildPaymentEnvelope` | fromAccount, toAccount, amount, timestamp | Payments |
| `buildTellerDepositEnvelope` | account, institution code, amount, timestamp | Deposits (signed by the institution) |
| `buildDepositEnvelope` | account, amount, timestamp | *Deprecated* — deposits predating tellers |
| `buildStatementEnvelope` | account, period, opening+closing balance, generatedAt | Statements |
| `buildSettlementMessageEnvelope` | uetr, both agents' codes, amount, xmlHash, createdAt | Interbank pacs.008 messages |

`buildCombinedEnvelope` exists as a **separate** method rather than an evolution of
`buildEnvelope`, specifically so a transfer without a payload still rebuilds the exact
string it was originally signed with — extending the original envelope in place would
have invalidated every signature issued before the ISO 20022 module existed. Which form
applies to a given transfer is decided by whether `xmlHash` is non-null, not by a
version flag, so reconstruction is deterministic from the row itself.

### 7.3 At-rest encryption (`FileEncryptionService`)

AES-256-GCM, applied transparently inside `FileStorageService` — every other service
reads and writes plaintext; encryption and decryption happen exactly once, at the
storage boundary. This is what let it be added without touching a single existing
signature: hashing and signing happen on plaintext held in memory *before* a file is
ever written to disk.

- **A fresh, randomly generated data-encryption key (DEK) per file**, wrapped under one
  master key (from configuration; a KMS/HSM swap is deferred to deployment, see §9.1)
  and stored in the file's own header — not in a database column, so a stored file
  carries everything needed to decrypt it except the master key.
- **GCM authenticates the ciphertext.** Tampering with a stored file fails decryption
  outright (`Failed to decrypt stored file — it may have been altered`) rather than
  producing corrupted-but-readable plaintext. This is a distinct guarantee from the
  Ed25519 signature: GCM proves the stored bytes weren't altered *underneath* the
  signature; the signature proves who produced the plaintext in the first place.
- **Backward compatible by construction.** A 6-byte magic header (`MLDSA1`) identifies
  a file this service wrote; anything without it is passed through unchanged, so files
  stored before encryption existed are still readable.

### 7.4 The stated limitation: no true non-repudiation

Both halves of every account's Ed25519 key pair are generated at registration and
stored server-side (`User.publicKey`, `User.privateKey`). Signing proves a file wasn't
altered *after this server processed it* — it does not prove non-repudiation between
two mutually distrusting parties, because this server itself holds every key needed to
have signed on any account's behalf. Closing this gap fully would require private keys
to live somewhere this server cannot read at will (client-side signing, or an HSM) —
a real architectural change, not attempted here, and named as such rather than implied
away.

### 7.5 XML canonicalization (`XmlCanonicalizationService`)

XML has representational flexibility a PDF doesn't — attribute order, `<B/>` vs.
`<B></B>`, presence of an XML declaration — that changes the serialized bytes without
changing the document's meaning. Hashing raw bytes would mean a recipient's own XML
library re-serializing the identical document could compute a different hash and look
tampered. W3C Exclusive Canonicalization (`javax.xml.crypto`, no external dependency)
is applied before hashing, so the hash is a property of the document, not of whichever
library wrote it. **Whitespace is still significant** — canonicalization normalises
serialization quirks, not formatting — so the payload is stored and transmitted
byte-identical to what was signed.

---

## 8. File Transfer Lifecycle — the Full State Machine

```
                    send (Institution)
                         │
                         ▼
                      ┌──────┐
           ┌──────────┤ SENT │──────────┐
           │          └──────┘          │
       approve                       reject
   (re-verifies                  (no integrity
    integrity;                    check required;
    throws if it                  reason mandatory)
    fails)                              │
           │                            ▼
           ▼                      ┌──────────┐
      ┌──────────┐                │ REJECTED │  ← terminal
      │ APPROVED │                └──────────┘
      └────┬─────┘
           │  download (first time)
           ▼
      ┌────────────┐
      │ DOWNLOADED │  ← terminal on the happy path
      └────────────┘
```

**Preview and the document view are available at every status**, including after a
decision — a rejected transfer can still be re-opened to see why it was rejected.
Only the *pickup* actions (`downloadFile`, `downloadPayload`) are gated, by
`requirePickupAllowed`: refused outright from `SENT` ("must be previewed and approved
first") and from `REJECTED` ("was rejected and cannot be downloaded"); allowed from
`APPROVED` or `DOWNLOADED`.

### 8.1 Two integrity-check contracts, deliberately different

`FileTransferService` exposes the same underlying check (`checkIntegrity` — re-hash the
stored file, re-canonicalize and re-hash the payload if one exists, rebuild the
envelope from persisted values, verify the signature) through **two different
contracts**, chosen per endpoint by what that endpoint needs to promise:

- **Throwing** (`verifyIntegrityOrThrow`) — used by `downloadFile`, `downloadPayload`,
  `approve`, and the document-preview endpoint. These either serve real bytes or record
  a real decision; there is no safe partial outcome, so a failed check aborts the
  operation.
- **Resilient** (`preview`, via `safeCheckIntegrity`) — never throws. A failed check
  comes back as `integrityValid: false` with a human-readable reason in the response
  body, because the entire purpose of a review step is to surface exactly this kind of
  problem to a reviewer, not hide it behind a generic error page. Payload fields are
  parsed and shown only when the check passes — content that fails verification isn't
  summarised as fact.

`safeCheckIntegrity` specifically has to wrap the *resource-loading* step, not just
`checkIntegrity` itself: with encryption in place (§7.3), the most realistic form of
tampering — flipping a bit in the stored ciphertext — fails AES-GCM authentication
during decryption, before there's any plaintext for `checkIntegrity`'s own hash
comparison to run against. Wrapping only the check itself would have left the resilient
promise true for a much narrower class of problems than the one this module exists to
catch; this was found and fixed during implementation by testing against an actually
corrupted file, not assumed to work from reading the code.

### 8.1.1 Approving a slip disburses it

`approve` is also where a payment instruction becomes money (`SlipDisbursementService`). The
transfer row is locked, integrity is re-verified, and only then is the payload parsed for the
amount and the two account numbers — the money that moves is read from the document that was
just proven unaltered. The disbursement runs through `PaymentService.disburse`, the same code
`pay` uses once it has resolved its accounts, so a payroll obeys the caps, the overdraft rule
and the settlement routing without a second implementation of any of them.

It happens at approval rather than at send because rejecting has to stay free: with no reversal
mechanism (see `REMAINING-WORK.md` §3.1), money moved at send would need one as soon as a
recipient declined. A disbursement that fails throws, rolling the approval back to `SENT`.

### 8.2 Why approve re-verifies instead of trusting an earlier preview

`approve` re-runs the full (throwing) integrity check at the moment of the decision,
rather than trusting whatever a preview call found earlier. Approving is a claim about
one specific document, right now — the gap between a preview and the approval that
follows it, however small, is exactly the kind of window this project's signing model
has consistently tried to close elsewhere (see the generate-then-sign-then-persist
pattern applied to every other signed document in the system).

---

## 9. Known Limitations, Stated Directly

### 9.1 Key custody

The Ed25519 signing keys, the AES-256-GCM master key, and the JWT secret are all held
by the running application, sourced from configuration. This defends against a stolen
disk, a database dump, or a copied backup — it does not defend against compromise of
the running server itself, which would expose all three. Moving custody to a KMS or
HSM is explicitly deferred to the deployment stage, not attempted here; every place
this applies is named as such in the code and in `README.md` rather than implied away.

### 9.2 `ddl-auto=update` does not revise constraints

Discovered during this module's implementation, not theoretical: Hibernate 6 generates
a Postgres `CHECK` constraint from an enum's values **at table-creation time**, and
`ddl-auto=update` never revisits that constraint when the entity's enum later gains
values. Extending `TransferStatus` from two values to four left a stale
`file_transfers_status_check` constraint rejecting the two new values outright — caught
by testing the actual approve path against the running database, not by reading the
code, and fixed by dropping the constraint by hand (Postgres still gets valid values
from the Java enum either way). This will recur for any future enum extension on an
existing table under this migration strategy; a real migration tool (Flyway/Liquibase)
would need to own this going forward rather than `ddl-auto=update`.

**Resolved going forward:** Flyway was adopted at the two-tier reset (§4.1). Enum CHECK
constraints now live in versioned migrations, so extending an enum means writing the
migration that revises its constraint. `ddl-auto=validate` won't do that for you.

### 9.3 Everything else already named in `README.md`

Deployment-stage KMS/HSM custody, no audit trail beyond the signature fields
themselves, and the deliberately-out-of-scope items (regulatory reporting, KYC/AML,
event-bus microservices, fraud/velocity monitoring, ML-KEM, zero-knowledge proofs) are
covered in the README's Roadmap section rather than repeated here.

---

## 10. Frontend Architecture

```
src/
├── api/
│   ├── client.ts     Typed fetch wrappers, one per endpoint. authHeaders() attaches
│   │                 the bearer token; handleResponse() unwraps the backend's
│   │                 {timestamp, status, message} error shape into a plain Error
│   └── session.ts     localStorage read/write for the signed-in session
├── components/        Shared UI: TransferTable, TransferReviewDialog, Pager,
│                     StatusChip/SignatureChip, the money-movement and card dialogs
├── context/            AuthContext — the signed-in user, restored from storage and
│                     re-validated against /auth/me on load
├── hooks/
│   └── usePagedResource.ts  One hook backing every paged list in the app — page
│                             state, loading, and a refresh() that steps back a page
│                             if the current one comes back empty (e.g. after a delete)
├── pages/              One per route: LoginPage (with first-time setup on an empty
│                     system), AccountPage (Normal User), DashboardPage (Institution:
│                     customers and transfers), BankDashboardPage (Central Bank console),
│                     PayLinkPage (the page a shared payment link opens to)
├── routes.ts            Maps each Role to its post-login landing route
└── types/index.ts       TypeScript types mirroring every backend DTO exactly,
                        including Page<T> and PaymentPreview
```

### 10.1 Routing and role-based landing

`ProtectedRoute` wraps every authenticated page, checking both "is there a session"
and "does this session's role match what this route requires" — a Normal User hitting
`/dashboard` (Institution-only) is redirected, not shown a broken page. `routes.ts`
is the single place that maps a `Role` to where it lands after login, so login and the
router can never disagree about where a role belongs.

### 10.2 The review dialog (`TransferReviewDialog`)

Fetches the structured preview and the rendered document **independently** on open —
their failure modes mean different things to a reviewer, so one failing doesn't block
the other from rendering. The document is fetched as a `Blob` and turned into an
object URL (`URL.createObjectURL`) rather than set directly as an `<iframe src>`,
because a plain iframe request can't carry the `Authorization` header the endpoint
requires; the object URL is explicitly revoked when the dialog closes or the
underlying transfer changes, to avoid leaking one blob URL per transfer ever opened
over the life of the page. The Approve button is disabled client-side whenever the
preview reports a failed integrity check — the backend would refuse the same request
regardless, but there is no reason to make a reviewer discover that by clicking; the
warning banner above the buttons already explains why.

---

## 11. Verification Method

Every module described above was checked against the running system while it was
built, not assumed correct from reading the code:

- **Backend**: `mvn compile` for correctness, then exercised through `curl` against a
  live PostgreSQL instance — including negative cases (wrong role → 403, tampered
  ciphertext → decryption failure, re-approving an already-reviewed transfer → refused,
  rejecting with no reason → refused) and platform-wide invariants (e.g. summing every
  deposit and posting independently and confirming it equals the admin console's
  reported total).
- **Frontend**: `npm run build` (`tsc` + `vite build`) for type correctness, then
  driven with a headless Playwright browser against the built output — real login,
  real clicks through the review dialog, a real approve decision, and a screenshot
  confirming the rendered layout, not just that the request succeeded.
- **Repeatable suites**, added with the two-tier restructure and runnable against a live
  backend on an empty database: `bank-backend/scripts/verify-two-tier-phase1.sh` (the
  tenancy and provisioning refusals of §5.4), `verify-two-tier-phase2.sh` (institution-signed
  statements and bank-numbered account/card numbers, verified outside the application),
  `verify-two-tier-phase3.sh` (a mixed intra/inter workload, the net debit cap, a concurrent
  pair of inter-bank payments, and invariants I1–I5 in SQL),
  `verify-iso20022-pacs008.sh` (the interbank message validated against the official schema
  and its signature verified outside the application, plus who may read it and what happens
  when a stored message is tampered with), `verify-slip-disbursement.sh` (a slip moves money on
  approval and only then, pays once however many approvals arrive, and fails its approval
  rather than the ledger when it cannot be afforded), and
  `bank-frontend/scripts/verify-two-tier-ui.mjs` (the same hierarchy built through a real
  browser).
- **Independent validation**: the generated ISO 20022 payload was validated with
  `lxml` against the official schema, outside the application entirely, rather than
  trusting the app's own validator to mark its own homework. Signed statements were
  independently verified with Python's `cryptography` library against the account's
  public key, reconstructing the signed envelope from the printed page exactly as an
  external verifier would have to.
