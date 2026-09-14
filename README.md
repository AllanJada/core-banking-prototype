# A Secure Core Banking System

Status: work in progress

## Overview

This began as a system for transferring signed files between financial institutions and
has since been reshaped into a small core banking system. It now carries a double-entry
ledger with derived balances, retail banking operations (deposits, payments, pay-by-link,
debit cards, signed statements), ISO 20022 `pain.001` payment payloads generated alongside
each document, token-based authentication with role-based access control, at-rest
encryption of stored files, and an oversight console for the operating bank.

What it still does not do is stated plainly rather than implied: it does not provide true
non-repudiation, because this server holds every account's private key (see Key Custody),
and its encryption and signing keys come from configuration rather than a managed key
store. Both are recorded below rather than left to be discovered.

Companion documents sit beside this one:

| Document | What it covers |
|---|---|
| [`FLOWS.md`](FLOWS.md) | Each flow step by step and what every step is *for*  plus where zero-knowledge proofs would fit if they were added |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | The shape of the whole system at once: request path, data model, security model |
| [`DATABASE-SCHEMA.md`](DATABASE-SCHEMA.md) | Every table and column, the DDL to create them, and the invariant queries |
| [`REMAINING-WORK.md`](REMAINING-WORK.md) | What is missing or wrong, honestly  including defects that can lose money today |
| [`TWO_TIER_BANKING_PLAN.md`](TWO_TIER_BANKING_PLAN.md) | The two-tier restructure and the decisions behind it |

## Tech Stack

### Backend

- Java, Spring Boot
- Spring Web (REST API)
- Spring Data JPA
- Spring Security (password hashing and CORS configuration)
- Ed25519 digital signatures (native JDK, JEP 339  no external crypto provider)
- Thymeleaf (HTML templating for generated documents)
- Microsoft Playwright for Java (headless Chromium PDF rendering)
- PostgreSQL
- Lombok
- Maven

### Frontend

- React
- TypeScript
- Vite
- React Router
- Material UI (MUI)

### Database

- PostgreSQL, accessed through Spring Data JPA with Hibernate's schema auto-update
  enabled for this stage of development

## Architecture

The backend follows a feature-sliced, layered structure:

```
org.learning.mldsa
  configs        Security and CORS configuration
  controllers    REST endpoints (auth, users, accounts, payments, payment links,
                 cards, file transfers, slips, admin console)
  dtos           Request and response payloads, including the shared page wrapper
  exceptions     Centralized error handling
  models         JPA entities
  repositories   Spring Data JPA repositories
  security       JWT issuing and verification, the authentication filter and principal
  services       Business logic, the ledger, cryptography, file encryption,
                 PDF and ISO 20022 generation
resources/iso20022   The official pain.001.001.09 schema, used for both code
                     generation and runtime validation
resources/templates  Thymeleaf templates for generated payslips and statements
```

Tables are split across Postgres schemas by owning module  `identity`, `ledger`,
`payments`, `cards` and `filetransfer`  so each module owns its own storage rather than
sharing one flat namespace.

The frontend is a single-page application structured as follows:

```
src
  api          Typed fetch wrappers for each backend endpoint, plus session storage
  components   Shared UI components (tables, dialogs, status chips, pager)
  context      Authentication state
  hooks        Paged-resource state shared by every table
  pages        Login, personal banking, institution dashboard, admin console,
               pay-by-link
  routes.ts    Which dashboard each role lands on
  types        Shared TypeScript types matching backend DTOs
```

## Current Functionality

### Accounts and Access

- Three roles under role-based access control, organised as a two-tier banking system:
  normal users (retail customers of one institution), institutions (commercial banks),
  and the Central Bank (supervision)
- One centralized login screen for all three. The account's role decides which
  dashboard it lands on after signing in, rather than which page it started from 
  the separate "Bank Login" route and the hamburger menu between the two are gone
- Institutions land on a tabbed dashboard (Customers / Inbox / Outbox); Central Bank
  overseers land on the Central Bank console; normal users land on their personal
  banking page
- Accounts are provisioned along a chain of custody (see Two-Tier Provisioning); passwords
  are hashed with bcrypt
- Every account is provisioned with its own Ed25519 key pair at creation time

### Accounts and Ledger

- Each customer's institution opens their account, with a 16-digit account number whose
  first three digits are that institution's own bank number  the way a real account number
  identifies the bank holding it. The prefix is for people to read; which bank holds an
  account is a relationship in the data, so nothing routes on those digits
- Balances are **derived**, not stored: an account has no balance column at all. The
  figure shown is summed from that account's postings whenever it is asked for, so the
  balance and the history it came from cannot disagree
- A `Posting` is one movement of money against one account, recorded append-only. Money
  is moved by writing a new posting, never by editing or deleting an existing one, which
  is what makes a balance reconstructable rather than merely current
- Amounts are stored as `numeric(19,2)` and always positive; a separate direction
  (`CREDIT` / `DEBIT`) carries the sign, so a malformed row cannot silently reverse a
  transaction's meaning
- Postings that make up a single operation share a transaction reference, so the two
  halves of a transfer can be identified as a pair rather than inferred from timestamps
- Customers can view their own account and its history

### Deposits and Payments

- A deposit credits the customer's own account: one posting, plus a signed record of it
- A payment moves money between two accounts, identified by account number. Its debit and
  credit are written together in one transaction, so a debit can never be committed
  without its matching credit
- Each is signed with the customer's own key at the moment it is made, binding the
  accounts, the amount, and the timestamp  the same generate-then-sign-then-persist
  shape the slip flow uses. Amounts are normalised to two decimal places before signing,
  so the same sum always produces the same signature
- **No overdrafts**: a payment that would take an account below zero is refused
- A per-transaction cap and a per-account daily cap (UTC day) bound what can leave an
  account, both configurable. Deposits are not capped, since they only add
- **Refused payments are kept**, with the reason. Because a rejection rolls back the
  transaction that would have made the payment, the record is written in a separate
  transaction so it survives  a customer asking "why didn't that go through" has an
  answer. Nothing that did not happen is signed, and no refused payment has postings
- Payments settle synchronously, so there is no PENDING state: a payment either completed
  or it moved no money at all

### Inter-Bank Settlement

- **A payment between customers of the same bank writes two postings**, exactly as before 
  no money leaves the bank, so settlement is not involved
- **A payment between customers of different banks writes four**, under one reference in one
  transaction: the payer and their bank's settlement account are debited, the payee and their
  bank's settlement account are credited. Each bank's books stay balanced, and the four
  postings net to zero. Which route a payment takes is decided by comparing the two accounts'
  institutions, never by anything the payer sends
- **Settlement positions are derived from postings**, like every other balance, so the
  Central Bank's view cannot drift from the ledger it describes. A negative position means
  that bank currently owes the rest of the system
- **A net debit cap bounds how far a position may go negative.** A payment that would breach
  it is refused and recorded. The customer is told only that the payment could not be settled
   naming a bank's liquidity to them would leak it  while the specific cause is visible to
  their own institution and to the Central Bank
- **Two payments leaving one bank at once cannot both slip through** a cap that only has room
  for one: the paying bank's settlement account is locked before its position is read. Only
  the debited side is locked, so two banks paying each other at the same moment cannot
  deadlock
- **Each inter-bank payment also produces an ISO 20022 `pacs.008`**  the instruction the
  paying bank sends the receiving one  written in the same transaction as the money it moves
  (see ISO 20022 `pacs.008` Interbank Messages)
- The invariants (positions always sum to zero, every payment's postings net to zero, an
  intra-bank payment never touches settlement) are **checked in SQL against the running
  database** by `bank-backend/scripts/verify-two-tier-phase3.sh`, and the zero-sum check is
  recomputed on every read of the Central Bank's console

### Account Statements

- A customer can download a statement for any date range as a PDF, rendered through the
  same Thymeleaf and headless-Chromium pipeline that produces payslips  which is what
  makes a long statement paginate correctly without any page-layout code
- Each statement shows an opening balance, every posting in the period with a running
  balance, and totals in and out. Opening balance plus the period's movements always
  equals the closing balance
- Nothing is stored when a statement is produced. Because balances are derived, a
  statement recomputes its opening balance by summing every posting before the period, so
  regenerating one gives the same figures and consecutive periods always agree
- Periods are whole UTC days, half-open internally (start inclusive, end exclusive) so
  consecutive statements abut exactly rather than double-counting or missing a posting
  landing on a boundary
- Each statement is **signed with the issuing institution's key**, over the account number,
  the period, and the opening and closing balances. A statement is a document the bank issues
  about an account, so the bank is the party attesting to its figures  not the customer. The
  signature and the exact values it covers are printed on the document, so it can be checked
  against the institution's public key using only what is on the page; altering any printed
  figure leaves a signature that no longer matches. Note this signs the statement's figures,
  not the rendered bytes: a PDF cannot contain a signature computed from itself, and since the
  server holds every private key it is not proof against this server (the same limitation
  recorded under Key Custody below)
- The page is **headed with the institution's name and bank code**, so a customer's statement
  names the bank that issued it rather than implying the central bank did

### Debit Cards

- A customer can issue one card against their account, with a 16-digit number distinct from
  the account number and **Luhn-valid**, like a real card, so a mistyped digit fails a
  checksum instead of addressing some other card. It opens with a 4 and then the issuing
  institution's bank number, where a real card carries its issuer identification  those
  digits replace random ones, so the number stays 16 long and still checksums
- **Cardholders are verified by PIN**, stored as a bcrypt hash exactly like an account
  password. Nothing in the system can read a PIN back  it can only confirm one
- **No CVV is stored at all.** PCI DSS forbids retaining the card verification value after
  authorisation, and with PIN verification there is nothing it would add to justify holding it
- Three consecutive wrong PINs block the card. The attempt count is committed in its own
  transaction, because reporting a wrong PIN means throwing, and a counter updated in the
  rolled-back transaction would reset on every attempt  leaving all 10,000 PINs open to
  being tried in turn
- The full card number is returned exactly once, when the card is issued; every later read
  is masked to the last four digits
- Blocking is one-way for the customer: unblocking is done by the customer's own
  institution, from its dashboard, and by no other bank
- Cards expire at the end of their month, and EXPIRED is derived from the date rather than
  stored, for the same reason it is on payment links

### Pay By Link

- A customer can create a shareable request for payment into their own account, with a
  deadline. Anyone signed in who opens the link can pay it, since holding the link is
  what entitles someone to pay
- Paying a link creates an ordinary payment, so every rule that governs a normal payment
   sufficient funds, the caps, signing, the paired postings  applies unchanged rather
  than being a second way to move money
- **Expiry is evaluated when the link is paid**, not only when it was created: a link is
  shared precisely so it can be opened later, and it must stop working the moment its
  deadline passes
- EXPIRED is derived from the clock rather than stored. Persisting it would need a job
  sweeping links at their expiry, and a link would read as payable for as long as that
  sweep was late
- A link is payable exactly once. The row is locked while it is being paid, so two people
  paying the same link at the same moment cannot both be charged
- If a payment is refused, the link stays open and can be paid again once the problem is
  fixed  while the refusal is still recorded against the payer
- Opening a link while signed out returns you to it after signing in, rather than dropping
  you on your own dashboard with the link lost

### File Transfer

- Files can be sent two ways: composing a structured payslip that the server
  generates as a signed PDF (see below), which is currently the only supported way
  to send a file. A previous raw file-upload mechanism was removed from both
  dashboards in favor of this generate-and-sign flow, specifically to prevent a
  file's contents from being altered between creation and sending
- An inbox view, scoped to the signed-in account, listing files sent to it
- An outbox view, scoped to the signed-in account, listing files it has sent and
  each one's current status
- A four-state lifecycle per transfer: **SENT** → (**APPROVED** or **REJECTED**) →
  **DOWNLOADED**. Only SENT is reachable indirectly  every other state follows from an
  explicit recipient decision (see Transfer Review below)
- Files are stored on disk under a server-generated identifier, never under their
  original or claimed filename

### Transfer Review

Before a recipient can pick up (download) an incoming transfer, they must preview it and
explicitly approve or reject it  downloading is no longer something a SENT transfer
allows directly.

- **The preview is resilient by design.** A structured summary endpoint re-verifies the
  file's integrity on every call and reports the result in the response body rather than
  throwing  `integrityValid: false` with a reason, not a failed request  because the
  point of a review step is to surface exactly this kind of problem to a human, not hide it
  behind an error page. This had to account for encryption specifically: a corrupted file
  fails AES-GCM authentication *before* there is any plaintext to hash-compare, so the
  resilient check wraps that failure too, not just the narrower hash-mismatch case
- **The payment instruction is parsed, not just linked.** When a transfer carries an ISO
  20022 payload, the preview reads the actual pain.001 message back out  debtor, creditor,
  amount, currency, execution date, remittance information  so a reviewer sees what is
  being paid without reading XML by eye. Parsed fields are only shown when the integrity
  check passes; content that fails verification is not summarised as fact
- **The rendered document is available inline**, separately from the structured summary,
  and unlike the summary it does throw on a failed check  there is no safe way to render a
  tampered PDF "with a warning attached", so a reviewer is directed back to the resilient
  summary instead, where the failure is explained
- **Approval re-verifies at the moment of the decision**, not from a cached result  the
  file on disk could have changed since an earlier preview, however unlikely, and approving
  is a claim about this specific document, right now
- **Rejection requires a reason** and needs no passing integrity check  refusing a
  transfer is always available, including on one that has already failed to verify
- Both decisions are terminal and reachable only from SENT: once a transfer is APPROVED,
  REJECTED, or DOWNLOADED, it cannot be decided again

### Cryptographic Signing

- Every file transfer is signed with Ed25519 at the moment it is sent, using the
  sending account's own private key. This replaced ML-DSA-65 (post-quantum, FIPS 204)
  when the project pivoted toward core banking: Ed25519 is built into the JDK, needs no
  third-party provider, and has keys and signatures measured in tens of bytes rather
  than kilobytes. The tradeoff is deliberate  it gives up resistance to a future
  quantum attacker, and the post-quantum research remains on file if that changes
- The file's SHA-384 hash, sender, receiver, filename, and timestamp are bound
  together into a signed envelope, not just the file content alone
- The system independently rehashes the file as it currently exists on disk and
  re-verifies the signature against the sender's stored public key on preview, on
  approval, and on every download  not just once. A failed check is recorded and the
  operation that triggered it is refused, except at preview, which reports the failure
  instead of refusing (see Transfer Review)
- The inbox and outbox both display the signature status of each transfer, honestly
  distinguishing a file that has been signed but not yet reviewed (SIGNED) from one
  that has actually been re-verified (VERIFIED) or has failed verification (INVALID).
  Once a transfer reaches APPROVED, verification has necessarily already succeeded 
  there is no path to that status otherwise

### Server-Generated Documents

- A "Compose" flow lets a user fill in structured payslip fields (employee details,
  earnings, deductions, accounts) rather than uploading a file directly
- The backend renders this into a PDF using a Thymeleaf template and a headless
  Chromium instance (Playwright), then signs the exact rendered bytes in the same
  request, so there is no point at which the document's contents could be swapped
  before signing
- The frontend can preview a locally selected PDF (for direct uploads, where still
  applicable to older data) before it is sent, using the browser's native PDF
  renderer

### ISO 20022 `pain.001` Payloads

Every slip is sent with an ISO 20022 `pain.001.001.09` customer credit transfer initiation
generated beside it, from the same data, under one signature.

- **Generated from the official schema.** The XSD published by ISO 20022 is in the repo, and
  JAXB generates the binding classes from it at build time. The schema is the source of
  truth: if it changes, the generated classes change and the compiler finds every mapping
  that no longer fits
- **Schema validation is a hard pre-send gate.** Every payload is validated against that
  same XSD and rejected on failure  the standard's value is that a receiver can refuse a
  malformed message outright, and a generator that emitted invalid XML would give that up.
  The payload is built and validated *before* anything is stored or signed, so a slip that
  cannot produce a valid message leaves no signed PDF behind
- **Slips whose data cannot map are refused with a reason naming the field**, rather than
  surfacing a raw schema error. This is where the address rule from the phase below becomes
  a hard requirement: no town or country, no payload
- **Canonicalised before hashing.** XML can be serialised several equally correct ways 
  attribute order, `<B/>` versus `<B></B>`, the XML declaration  and the bytes differ while
  the meaning does not. The payload is reduced to its canonical form (W3C Exclusive C14N,
  from the JDK, no extra dependency) before it is hashed, so a recipient who re-serialises
  it still computes the same value. Whitespace *is* preserved by canonicalization, so
  reformatting the document will still change the hash; that is the standard's behaviour,
  and the payload is stored and transmitted exactly as signed
- **One signature covers both artefacts.** The envelope binds the PDF hash, the XML's
  canonical hash and the UETR together. Signing them separately would leave them swappable:
  a valid PDF signature and a valid XML signature from two different transfers could be
  presented as a pair, with the two documents disagreeing about the amount
- Both are re-verified on download. Altering the amount in the stored payload makes the
  document download fail too, not just the payload download
- A payslip is **not** an ISO 20022 message  the catalogue models payments, not employment
  documents. What is expressed is the credit transfer the slip describes: net pay moving
  from the employer's account to the employee's. The earnings and deductions breakdown has
  no counterpart in the standard and stays on the PDF, with a short unstructured remittance
  line carrying the pay period. Structured remittance models invoices and creditor
  references, so forcing payroll into it would be a misuse rather than a mapping

### Payroll Disbursement

A slip's `pain.001` describes a credit transfer. Approving the slip is what executes it.

- **The receiving institution's approval moves the money**  sending moves nothing, and
  rejecting moves nothing. That ordering is deliberate: this system has no reversals, so money
  moved at send would need one the moment a recipient said no
- **What moves is what was signed.** The amount and both account numbers are read out of the
  stored payload *after* its integrity has been re-verified, so there is no second copy of the
  amount that could drift from the document
- **The accounts have to be real, and have to be the right ones**: the payer a customer of the
  sending bank, the payee a customer of the receiving one. A slip cannot debit an account at a
  bank that never saw it. This is checked when the slip is composed, so a bad account number is
  caught while its author is still looking at the form, and again at approval, which is the
  check that counts
- **It goes through the ordinary payment path**, so every rule applies unchanged  the caps, no
  overdraft, settlement routing between banks, and the `pacs.008` that comes with it. A payroll
  paid across banks settles exactly like any other inter-bank payment
- **The transfer row is locked while it is decided**, so two approvals arriving at once cannot
  pay the same slip twice; a unique constraint on the database says the same thing
- **A disbursement that cannot be made fails the approval** rather than the ledger: the slip is
  left awaiting review, no money moves, and the refusal is recorded so the paying bank can see
  why its payroll did not go out
- The full path is covered by `bank-backend/scripts/verify-slip-disbursement.sh`

### ISO 20022 `pacs.008` Interbank Messages

A `pain.001` is what a customer sends their own bank to *initiate* a transfer. A `pacs.008` is
what that bank then sends the receiving bank to *settle* it. The system had no use for the
second until payments started crossing institutions.

- **Every inter-bank payment writes one**, and a payment inside a single bank writes none 
  nothing crosses an institution, so there is no bank to instruct
- **Generated from the official ISO schema**, like `pain.001`: the XSD is in the repo, JAXB
  generates the binding classes from it at build time, and the same file validates the output
- **Written inside the payment's own transaction**, so money and the instruction that moved it
  commit together. A message that cannot be generated or schema-validated takes the payment
  down with it, rather than leaving a settled transfer nobody can evidence
- **Settlement method is `CLRG`**  both banks hold settlement accounts at the Central Bank and
  the money moves between them there, which is settlement through a clearing system rather
  than across either agent's own books
- **The message's UETR is the ledger's own transaction reference**, the same one tying the
  payment's four postings together, so the instruction and the money it moved share one
  identifier rather than two that could disagree
- **Signed by the sending bank** and stored encrypted. Downloading re-hashes the stored bytes
  against what was signed and re-checks the signature, so a tampered message is refused rather
  than served
- **Only the two banks party to it can read it**, plus the Central Bank as settlement operator.
  A third bank asking gets "not found", and customers have no access at all  this is an
  interbank message, not a customer document
- `bank-backend/scripts/verify-iso20022-pacs008.sh` validates a generated message against the
  official schema and verifies its signature **outside the application**, with `lxml` and
  `cryptography`

### ISO 20022 Data Model (Phase 1)

The groundwork the payload rests on  the parts that are expensive to retrofit because they
have to be captured at origination.

- **Addresses are structured**, mirroring ISO 20022's `PstlAdr`: street name, building
  number, post code, town and a two-letter ISO 3166-1 country code, replacing the single
  free-text line. Unstructured addresses are being withdrawn across the major schemes, and
  splitting a typed-in line back into components afterwards is guesswork. The document still
  displays one line, derived from the parts, so there is no second copy to disagree
- **Every transfer carries a UETR** (Unique End-to-End Transaction Reference), a UUID v4
  minted once at origination and never regenerated  a reference that changes as a payment
  moves breaks the audit trail while still looking valid. Transfers created before this
  field existed are left null rather than being given a reference that was never sent
- **Institutions may carry a BIC, and none here do.** This is a test system whose
  institutions are not SWIFT-registered, so the field is optional and generation will fall
  back to proprietary financial-institution identification. Inventing plausible-looking BICs
  would be worse than having none: a syntactically valid code belonging to a real bank is
  actively misleading
- **Accounts are identified by proprietary id, not IBAN.** Tanzania does not use IBAN, so
  the 16-digit account numbers belong in `Othr/Id`  schema-valid XML carrying an IBAN that
  is not one would be worse than useless to a receiving bank
- Target version is **`pain.001.001.09`**, pinned rather than assumed, since schemes adopt
  versions on staggered schedules
- The envelope change was made **once**, when the payload landed. Transfers without a
  payload  direct uploads, and everything sent earlier  still rebuild the original
  PDF-only envelope, so their signatures remain valid rather than being invalidated by a
  field they never carried. Which form applies is decided by whether a payload hash was
  persisted, so reconstruction stays deterministic
- The identifier fields (`MsgId`, `PmtInfId`, `EndToEndId`) are `Max35Text` and a UUID is 36
  characters, so they carry the reference without its hyphens while the `UETR` element takes
  the full form. Its schema type is a UUIDv4 pattern that enforces the version nibble
  directly  the standard itself requires v4, which is why the reference is generated that
  way at origination

### At-Rest File Encryption

Stored documents and payloads are encrypted with AES-256-GCM, closing the "anyone with
filesystem access can read file contents" gap recorded under the security notes.

- **A fresh data encryption key per file**, wrapped under a single master key. One key for
  everything would mean one compromise exposing every document; per-file keys also stay well
  inside the limits on how much data may safely be encrypted under one AES-GCM key
- **The wrapped key travels in the file's own header**, not in a database column, so a
  stored file carries everything needed to decrypt it except the master key. Splitting the
  two across disk and database would create a pairing that restoring one without the other
  could break
- **GCM is authenticated encryption**: altering stored bytes makes decryption fail rather
  than yield plausible rubbish. This is a separate guarantee from the Ed25519 signature over
  the plaintext, and both are kept  the signature proves who produced a document, GCM
  proves the stored bytes were not altered underneath it
- **Encryption is confined to the storage layer.** Hashes and signatures are computed over
  plaintext in memory before anything is written, and reads return plaintext, so introducing
  encryption changed neither  every signature issued beforehand is still valid
- **Files written before this existed are still plaintext and still readable.** Encrypted
  files are recognised by a magic header rather than a database flag, so the detection
  cannot disagree with what is actually on disk
- **What it protects against, stated honestly:** a stolen disk, a database dump, a copied
  backup. The master key comes from configuration, so it is held by the running application
   an attacker who compromises the running server has the key too. Closing that needs the
  key to live somewhere the application cannot read at will. **Moving key custody to a KMS
  or HSM is planned for the deployment stage**, and the wrapping is deliberately isolated in
  one class so that swap stays contained
- A file is read fully into memory to be encrypted or decrypted, since GCM authenticates a
  complete message. The multipart upload limit already bounds how large that can be

### Central Bank Console (Bank Role)

Supervision across the system, restricted to the Bank role at the controller rather than
per method.

- **An overview** of what the platform holds, how many customer accounts and participants
  exist, and how much is moving. The total held is summed from the ledger on request, not a
  stored figure, so it cannot drift from the postings it describes
- **Refused payments are shown alongside completed ones, with their reasons.** A console
  that only counted successes would flatter the system; what is being rejected is usually
  the more useful signal
- **Institutions as aggregates**: each institution's code, settlement account, customer
  count, customer funds held and settlement position. It never shows customers' names or
  individual balances, which stay with their own bank. Each figure is one grouped query for
  the whole page, not one query per institution
- **Settlement**: every institution's position, cap and headroom, whether the positions still
  sum to zero, the bank-to-bank movements behind them, and what could not be settled and why.
  Movements name banks, never the customers on either side
- **Transfers** across all participants, including each transfer's signature status and
  whether it carries an ISO 20022 payload
- **Nothing here touches customers' money.** There is no balance adjustment, payment
  reversal, card unblock or customer creation. The console's only writes are licensing
  institutions and adding overseers

### Two-Tier Provisioning

Every account is created by the tier directly above it, so there is always an identifiable
party responsible for its existence. This is Phase 1 of
[`TWO_TIER_BANKING_PLAN.md`](TWO_TIER_BANKING_PLAN.md).

- **First-time setup.** On an empty system, the sign-in page offers to create the first
  Central Bank overseer. That is all the anonymous window can create, and a request for any
  other role is refused. The window closes for good once an overseer exists. The check lives
  in the service because it depends on the database's state, not on the request
- **The Central Bank** licenses institutions, each with a unique code of 3–8 letters or
  digits, and adds overseers. An institution's settlement account is opened in the same
  transaction
- **Each institution also gets a three-digit bank number** when it is licensed, which prefixes
  every account and card number it issues. The letter code is for display; account and card
  numbers have to stay all-digits (and Luhn-checkable), which is why the numeric code exists
- **An institution** provisions its own customers from its dashboard. Each customer's
  account is opened at that institution in the same transaction. The institution comes from
  the token, never from the request, so no bank can open a customer at another
- **Separate endpoints per tier** (`/admin/institutions`, `/admin/overseers`,
  `/institution/customers`), each with a class-level role check, rather than one endpoint
  whose effect depends on a role field in the body
- **Tenant scoping.** An institution sees and manages only its own customers, including
  unblocking a card locked by wrong PINs. Another bank's customer gets "not found", the same
  answer as an id that doesn't exist, so there is nothing to probe
- **Customers can't pay settlement accounts.** A settlement account number is refused
  exactly like an unknown one. Settlement positions will move only through inter-bank
  settlement (Phase 3)
- `bank-backend/scripts/verify-two-tier-phase1.sh` runs the plan's negative tests against a
  running backend on an empty database

### Pagination

Every list endpoint returns one page rather than an unbounded array.

- **Bounded by default.** Omitting `page` and `size` gives the first page at a default size,
  so a client that never asks about paging still cannot pull an entire table
- **Page size is capped.** Out-of-range values are clamped rather than rejected  a caller
  asking for a million rows wants as many as it can have, not an error  but the cap is what
  stops pagination from becoming an instruction to load everything anyway
- Ordering stays part of each query rather than being a caller-supplied parameter: newest
  first is what an inbox *means*, not a preference
- The response carries `totalElements`, `totalPages` and `hasNext`. `hasNext` is derivable,
  but computing it in each client is how the clients end up disagreeing
- **Statements deliberately do not paginate.** A statement needs every posting in its period
  to reconcile, so it uses its own unpaged query. Paging it would silently produce a document
  whose closing balance did not follow from the lines above it
- The frontend shares one hook and one pager component across every table, so a table cannot
  forget to reset to the first page, and a page left pointing past the end of a shrunken list
  steps back instead of showing nothing

### Responsive Layout

Audited by measuring, not by eye: a headless browser loads each dashboard at phone (390px)
and tablet (768px) width and asserts that `documentElement.scrollWidth` never exceeds the
viewport.

- **No page overflows its viewport** at either width
- Wide tables are intentionally wider than a phone screen and scroll **inside** their
  container, verified as genuinely scrollable rather than clipped. Without a minimum width
  they would instead crush every column into unreadable slivers
- The Bank console's tabs scroll horizontally on a phone rather than compressing
- The signed-in identity in each toolbar is hidden on the narrowest screens so it can never
  push the sign-out control off the bar
- The account's action buttons wrap rather than overflowing, since three do not fit one
  phone-width row
- The permanent sidebar the pivot plan flagged is already gone  it was replaced by tabs when
  the Bank dashboard became an oversight console

### Error Handling

- Centralized exception handling returns structured JSON error responses rather
  than default framework error pages

## Authentication Model (Current Stage)

A successful login returns a signed JWT (HS256) carrying the account's ID and role.
The frontend sends it as a bearer token on every subsequent call, and the backend
derives the acting account from that token  no endpoint takes a userId parameter, so
inbox, outbox, and download queries cannot be re-scoped to another account by editing
a URL. Each endpoint declares which roles may call it, and those checks are enforced
server side rather than implied by which dashboard the frontend rendered.

Recipient lists are also produced by the backend (same-role counterparties, excluding
yourself) and the same rule is enforced again when a transfer is actually sent, so it
holds for callers who bypass the UI entirely.

What this stage still does not do: tokens cannot be revoked before they expire, there
are no refresh tokens, and the signing secret comes from configuration rather than a
managed key store.

## Key Custody (Current Stage)

Every account's Ed25519 private key is generated at account creation and stored
directly in the database alongside its public key. This means the server holds both
halves of every account's key pair. Signing proves a file was not altered after this
server processed it, but it does not yet provide genuine non-repudiation between two
institutions that do not trust the same central server, since the server itself is in
a position to have signed on any account's behalf. This is a known, documented
limitation, not an oversight, and is the primary item at the top of the roadmap below.

## Roadmap

The project was reshaped from a file-transfer system into a core banking system, one
module at a time. Identity and access (roles, centralized login, RBAC), the
crypto swap to Ed25519, the accounts/ledger foundation, deposits/payments, pay-by-link,
signed account statements, debit cards, ISO 20022 `pain.001` payloads, at-rest file
encryption, the bank role's admin console, pagination, the responsive audit, and
inter-institutional transfer review are done.

The core banking pivot's module list is complete.

**In progress: two-tier banking.** The Central Bank provisions institutions rather than
customers, and institutions own their customers. Cross-bank payments will settle through
institution settlement accounts. The agreed decisions, design, and phased plan are in
[`TWO_TIER_BANKING_PLAN.md`](TWO_TIER_BANKING_PLAN.md). **All three phases are implemented**:
tenancy and the provisioning chain, institution-signed statements with institution-coded
account and card numbers, and inter-bank settlement with supervision. The plan's follow-up
`pacs.008` interbank message is implemented too.

Beyond that, what remains is deployment-stage work and the items named below as deliberately
out of scope:

- A checksum-and-audit trail beyond the signature fields already present

Deferred to the deployment stage:

- **KMS or HSM custody** for the file-encryption master key and the JWT signing secret,
  replacing the configuration-held values described above

An earlier post-quantum track was researched before this pivot. Two of its items have since
been built along different lines than that research assumed  ISO 20022 `pain.001` payloads
were, and at-rest encryption was done with AES-256-GCM rather than ML-KEM. What remains
unbuilt from it is ML-KEM key encapsulation and zero-knowledge proofs. The Ed25519 swap
moves deliberately away from a post-quantum posture, but that research stays valid if
quantum resistance becomes a priority again.

Full detail on each of these is maintained separately in the project's research
documents rather than duplicated here.

## Requirements

- Java 26 (the version the build targets; see `java.version` in `bank-backend/pom.xml`)
- Maven
- Node.js and npm
- PostgreSQL
- Google Chromium, installed via Playwright's own installer, for PDF generation

## Running the Backend

The backend is built and run using Maven. Database connection details and file
storage location are configured in `application.properties`.

Flyway creates and versions the schema on startup from the migrations in
`src/main/resources/db/migration`; Hibernate only validates it. A database created before
Flyway was adopted must be reset (dropped and recreated empty) before the application will
start against it. On first run after
adding the PDF generation dependency, Playwright's Chromium browser must be installed
separately; it is not bundled with the Maven dependency itself.

## Running the Frontend

```
npm install
npm run dev
```

The frontend expects the backend to be reachable at the URL configured in `.env`
(`VITE_API_BASE_URL`), and the backend's CORS configuration must allow the frontend's
origin.
