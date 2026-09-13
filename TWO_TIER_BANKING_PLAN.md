# Two-Tier Banking: Central Bank, Institutions, and Customers

Status: **Implemented — all three phases (see §13).** Every open question in §12 was resolved
by accepting its recommendation. What this plan deliberately left out is listed in §14.

This plan restructures the system from a flat platform (where the Central Bank provisions
everyone, customers included) into the two-tier model real banking systems use. A central
bank supervises commercial banks, and the commercial banks hold the customer relationships.
It builds on the core banking system described in `README.md` and `ARCHITECTURE.md` rather
than replacing it.

---

## 1. Decisions Made

| # | Question | Decision |
|---|---|---|
| 1 | Can customers pay customers at other banks, and how does that settle? | **Yes, through settlement accounts.** Each institution has a settlement account at the Central Bank, and cross-bank payments move through them |
| 2 | Is an institution one login or an organisation with staff? | **One login per institution** |
| 3 | What does the Central Bank see? | **Per-institution aggregates**, not individual customer balances or identities *(confirmed by accepting Q5)* |
| 4 | What happens to existing data? | **Start from a fresh database** |

---

## 2. Why This Model

Today the Central Bank (`BANK` role) can create any account, including retail customers,
and its console lists every customer's balance. Real central banks don't hold retail
accounts. The Bank of Tanzania supervises commercial banks, and those banks open and run
customer accounts. The proposed model puts each responsibility with the party that holds it
in practice:

- **The Central Bank** licenses institutions and watches the system as a whole.
- **Institutions** own their customers: they open accounts, issue cards, and answer for
  their customers' money.
- **Customers** bank with exactly one institution.

It also gives provisioning a **chain of custody**: every account is created by the tier
directly above it, so there is always an identifiable party responsible for an account's
existence.

---

## 3. Roles and Provisioning

```
Central Bank overseer (BANK)
   │  creates → Institutions, other Central Bank overseers
   ▼
Institution (INSTITUTION)            one login per commercial bank
   │  creates → Customers, permanently bound to that institution
   ▼
Customer (NORMAL_USER)               account · debit card · deposits · payments · pay-by-link
```

| Caller | May create | Side effects in the same transaction |
|---|---|---|
| Anonymous, **only while no `BANK` account exists** | `BANK` **only** | — |
| `BANK` | `INSTITUTION`, `BANK` | Creating an institution also opens its **settlement account** |
| `INSTITUTION` | `NORMAL_USER` only | Opens the customer's account **at the calling institution** |
| `NORMAL_USER` | nothing | — |

**Bootstrap is tightened.** Today `UserService.requireMayProvision` allows an anonymous
caller to create *any* role while no `BANK` account exists. Under a chain of custody, that
window must produce only the first Central Bank overseer. Otherwise an unsupervised
institution could be created before any supervisor exists.

**The institution a customer belongs to comes from the caller's token, never from the
request body.** An institution cannot open a customer at another bank by naming it.

---

## 4. Data Model Changes

### 4.1 Changes

| Entity | Change | Purpose |
|---|---|---|
| `Account` | Add `type: AccountType` (`CUSTOMER`, `SETTLEMENT`) | Distinguishes a customer's account from an institution's settlement position |
| `Account` | Add `institution → User` (non-null) | The institution the account belongs to. For a customer account, their bank; for a settlement account, the institution itself |
| `User` | Add `institutionCode` (institutions only, unique) | A short stable code, used for display and optionally as an account-number prefix (§11) |

Ownership stays one account per owner, which is what `AccountRepository.findByOwner_UserId`
(returning `Optional`) already assumes. A customer owns their customer account, and an
institution owns its one settlement account.

### 4.2 Resulting relationships

```
User [BANK]                                  (no accounts)

User [INSTITUTION] ──owns──▶ Account [SETTLEMENT, institution = itself]
        ▲
        │ institution
        │
Account [CUSTOMER] ◀──owns── User [NORMAL_USER]
   ├── Postings
   ├── DebitCard
   ├── Deposits, Payments, PaymentLinks
```

### 4.3 Unchanged

Postings stay append-only and balances stay derived. There is still no balance column
anywhere. The settlement design below is expressed entirely in postings.

---

## 5. Settlement Design

### 5.1 Two kinds of payment

Whether a payment settles across banks is decided by comparing the institutions of the two
customer accounts.

**Intra-bank** (both customers at the same institution): two postings, exactly as today.
Settlement accounts are not touched, because no money leaves the bank.

**Inter-bank** (customers at different institutions): **four postings, one
`transactionRef`, one database transaction**.

Worked example: Ann at *Alpha Bank* pays Ben at *Beta Bank* 50,000 TZS.

| Account | Type | Direction | Amount | Effect |
|---|---|---|---|---|
| Ann | CUSTOMER (Alpha) | DEBIT | 50,000 | Ann's balance −50,000 |
| Alpha Bank settlement | SETTLEMENT | DEBIT | 50,000 | Alpha's position −50,000 |
| Beta Bank settlement | SETTLEMENT | CREDIT | 50,000 | Beta's position +50,000 |
| Ben | CUSTOMER (Beta) | CREDIT | 50,000 | Ben's balance +50,000 |
| | | | **Net** | **0** |

Each bank's books still balance. Alpha's obligation to Ann fell by the same amount as its
settlement position, and Beta's obligation to Ben rose by the same amount as its position.
This is a simplified form of what Tanzania's TISS (the national real-time gross settlement
system) does between banks.

The existing ledger primitive already supports this shape: `AccountService.transfer` writes
paired postings under one `transactionRef` in one transaction. The inter-bank path extends
the same idea to four postings rather than inventing a second mechanism.

### 5.2 Invariants

These become executable checks, verified against the running database the same way the
existing "total held equals total deposits" invariant was:

| ID | Invariant |
|---|---|
| I1 | Sum of all posting effects = sum of all deposits *(unchanged from today)* |
| I2 | **Sum of all settlement balances = 0, always.** Every inter-bank payment debits one settlement account and credits another |
| I3 | Sum of all customer balances = sum of all deposits *(follows from I1 and I2)* |
| I4 | Every payment's postings net to zero; every deposit's net to its amount |
| I5 | An intra-bank payment never writes to a settlement account |

### 5.3 Restrictions

- **Customers can never pay a settlement account directly.** Payment and pay-by-link
  recipient resolution rejects `SETTLEMENT` accounts, so settlement positions move only
  through the inter-bank path.
- **Nobody deposits into a settlement account.** Deposits credit customer accounts only.
- Existing customer-level controls are unchanged: no overdraft, the per-transaction cap,
  the daily cap, and refused payments recorded with a reason.
- Settlement accounts can go negative, since a negative position means an institution is a
  net debtor to the system. Whether that should be capped is open question Q1 (§12).

---

## 6. Visibility Boundaries

| Data | Customer | Institution | Central Bank |
|---|---|---|---|
| Own account, balance, postings, card | ✓ | — | — |
| Its customers' identities and balances | — | **Own customers only** | Aggregates only |
| Its customers' payments | Own | **Own customers only** | — |
| Inter-bank settlement movements | — | Own side only | All (bank to bank, **no customer identity**) |
| Settlement positions | — | Own | All |
| File transfers between institutions | — | Own inbox and outbox | All (institution-level metadata) |
| Provisioning | — | Customers | Institutions and overseers |

This follows decision 3. The Central Bank supervises *institutions*, and customer
identities stay with the customer's own bank.

---

## 7. API Changes

### 7.1 New endpoints

| Endpoint | Role | Purpose |
|---|---|---|
| `POST /api/v1/admin/institutions` | BANK | Create an institution and its settlement account atomically |
| `POST /api/v1/admin/overseers` | BANK | Create another Central Bank overseer |
| `GET /api/v1/admin/institutions` | BANK | Paged: each institution with customer count, customer funds held, settlement position, payment volume |
| `GET /api/v1/admin/settlement` | BANK | Paged inter-bank settlement movements (bank, bank, amount, time, reference) and current positions |
| `POST /api/v1/institution/customers` | INSTITUTION | Create a customer and their account at the caller's institution |
| `GET /api/v1/institution/customers` | INSTITUTION | Paged list of the caller's own customers |
| `GET /api/v1/institution/customers/{id}` | INSTITUTION | One customer, scoped to the caller's institution |
| `GET /api/v1/institution/summary` | INSTITUTION | Own aggregates and settlement position |
| `POST /api/v1/institution/customers/{id}/card/unblock` | INSTITUTION | Unblock a customer's card *(open question Q4)* |

### 7.2 Changed endpoints

| Endpoint | Change |
|---|---|
| `POST /api/v1/users` | Narrowed to **bootstrap only**: creates the first `BANK` account while none exists, and refuses otherwise |
| `GET /api/v1/users` | Retired, replaced by `GET /admin/institutions` |
| `GET /api/v1/admin/accounts` | Retired: listed every customer's balance, which contradicts decision 3 |
| `GET /api/v1/admin/payments` | Replaced by `GET /admin/settlement` |
| `GET /api/v1/admin/summary` | Adds per-institution totals and the I2 settlement-sum check |
| `POST /api/v1/payments` | Routes intra-bank (2 postings) or inter-bank (4 postings); rejects settlement recipients |
| `GET /api/v1/accounts/me` | Adds the customer's institution |
| `GET /api/v1/accounts/me/statement` | Issued and signed by the institution (§9) |

### 7.3 Why provisioning is split into separate endpoints

A single `POST /users` whose permitted effect depends on the `role` field in the request
body puts the authorization decision inside business logic, which is where authorization
bugs tend to live. Separate endpoints let each carry one class-level `@PreAuthorize`, the
convention every other controller in this project already follows.

### 7.4 Unchanged

Authentication, customer card self-service (issue, change PIN, block), deposits, payment
links (which inherit inter-bank routing automatically, since paying a link resolves to an
ordinary payment), statements' date-range handling, file transfers, slips, ISO 20022
payloads, and the transfer review workflow.

---

## 8. Frontend Changes

| Page | Today | After |
|---|---|---|
| `BankDashboardPage` (Central Bank) | Overview, every customer account, every payment, transfers | Overview with I2 check, **Institutions** (aggregates), **Settlement** (positions and movements), Transfers; provisioning limited to institutions and overseers |
| `DashboardPage` (Institution) | Inbox and outbox | **Customers** tab with a provision-customer dialog, **Overview** (own aggregates and settlement position), Inbox, Outbox |
| `AccountPage` (Customer) | Account, card, money movement, histories | Shows the customer's bank; payments show the destination bank when it differs |
| `LoginPage`, routing | Role-based landing | Unchanged |

---

## 9. What This Corrects in the Current System

*All four are now corrected: card unblocking in Phase 1, the rest in Phase 2.*

- **Statements are signed by the wrong party.** Today `StatementService` signs with
  `signingKeyOf(account.getOwner())`, the *customer's* key. A statement is a document the
  bank issues about an account, so it should be signed with the **institution's** key.
  Every institution already has an Ed25519 key pair, since keys are provisioned for every
  account at creation. The envelope's shape doesn't change, only its signer.
- **Statements carry the Bank of Tanzania emblem.** A customer statement should name the
  issuing institution, not imply the central bank issued it. Recommend a text header with
  the institution's name rather than per-bank logo assets.
- **Card unblocking has no owner.** `CardService` documents unblocking as "a bank
  operation, not a customer one", but no role can currently perform it. The institution is
  that bank.
- **Payment and deposit signatures stay with the customer.** This is already correct: the
  customer is the party authorising the movement.

---

## 10. Tenant Scoping

This model introduces a boundary that doesn't exist today: **Institution X must never read
or change Institution Y's customers.** This is the classic insecure direct object reference
risk, and it is enforced in the service layer rather than the UI.

- Every institution-facing query derives the institution from the token and filters by it,
  for example `findByUserIdAndInstitution_UserId`.
- A cross-tenant request returns **"not found"**, not "forbidden", so a caller cannot probe
  which customer IDs exist at other banks. This matches the existing
  `findByTransferIdAndReceiver_UserId` pattern ("File not found, or you are not the
  recipient").

Negative tests required before Phase 1 is considered done:

| Attempt | Expected |
|---|---|
| Alpha Bank reads a Beta Bank customer by ID | Not found |
| Alpha Bank lists customers | Only Alpha's |
| Alpha Bank unblocks a Beta Bank customer's card | Not found |
| Institution creates an `INSTITUTION` or `BANK` account | Refused |
| `BANK` creates a customer | Refused |
| Anonymous caller creates anything once a `BANK` exists | Refused |
| Anonymous bootstrap requests `INSTITUTION` | Refused |
| Customer pays a settlement account's number | Refused |

---

## 11. Fresh Database Plan

1. **Reset the database.** Drop and recreate the application schemas so tables are created
   with the new columns and enum values. This also clears the stale-constraint issue
   recorded in `ARCHITECTURE.md` §9.2.
2. **Clear stored files** in `bank-backend/storage/files`. Every encrypted document and
   payload there belongs to a transfer row that will no longer exist.
3. **Recreate the test hierarchy through the new provisioning chain**, which also exercises
   it end to end: bootstrap one Central Bank overseer, which creates two institutions, each
   of which creates customers.

Both resets are irreversible. They will be confirmed immediately before running, even
though the direction is agreed here.

**Status: done (2026-09-12).** Flyway is adopted, with `V1__baseline_schema.sql` as the
baseline. `mldsa` was dropped and recreated, `storage/files` was emptied, and Flyway built
the new schema on the next startup. The test hierarchy was rebuilt through the provisioning
chain itself by `bank-backend/scripts/verify-two-tier-phase1.sh`, which passes all 45 checks
against the real database.

**Recommendation: adopt Flyway at this reset.** `ddl-auto=update` has already cost a real
bug, since it never revises a constraint once created. An empty database is the cheapest
moment a migration tool will ever be adopted. Later, it means baselining a live schema.
See open question Q2.

---

## 12. Open Questions

All five were resolved by accepting the recommendation.

| # | Question | Recommendation (accepted) |
|---|---|---|
| Q1 | May settlement positions go negative without limit? | **Allow negative positions, capped by a configurable net debit cap per institution.** A payment that would breach the cap is refused and recorded. The customer should see a generic reason ("the payment could not be settled"), while the specific cause is visible to the institution and the Central Bank. A customer-facing message naming another bank's liquidity would leak it |
| Q2 | Adopt Flyway at the fresh reset? | **Yes** (§11) |
| Q3 | Prefix account and card numbers with an institution code? | **Yes, in Phase 2.** Nothing depends on it, since routing uses `Account.institution`, but it matches how real account and card numbers identify their bank. Card numbers stay Luhn-valid |
| Q4 | May institutions unblock their customers' cards? | **Yes**: it resolves the gap noted in §9 |
| Q5 | Confirm decision 3 was read correctly | Aggregate supervision, per §6 |

---

## 13. Implementation Phases

Each phase ends in a working, verifiable system. Phase 1 is useful on its own.

### Phase 1: Tenancy and provisioning chain

- `AccountType`, `Account.institution`, `User.institutionCode`
- Fresh database, plus Flyway if Q2 is accepted
- Bootstrap narrowed to the first `BANK` only
- Split provisioning endpoints; an institution's creation opens its settlement account
- Institution customer management endpoints and UI
- Central Bank provisioning UI limited to institutions and overseers

**Done when:** the hierarchy can be built entirely through the UI from an empty database,
and every row of the §10 negative-test table passes against the running system.

**Implemented.** Where the build departs from the list above:

- **Pulled forward**, because §10's table is Phase 1's done criterion: institutions unblocking
  their customers' cards (Q4, listed under Phase 2), and refusing settlement accounts as
  payment recipients (listed under Phase 3). A settlement account number is refused exactly
  as an unknown number is, so customers can't discover which numbers are settlement accounts.
- **Added** so the hierarchy can be built from an empty database through the UI alone:
  a public `GET /api/v1/users/bootstrap` endpoint and a first-time setup dialog on the
  sign-in page.
- **Also tightened:** `GET /users/counterparties` is institution-only (it had listed every
  customer to any customer), `user_name` is unique in the database, and
  `AccountService.requireAccountFor` returns customer accounts only.
- **Retired:** `GET /users` and `GET /admin/accounts`.
- **Left for Phase 3:** `GET /admin/settlement`, `GET /institution/summary`, the payment-volume
  figure on `GET /admin/institutions`, and retiring `GET /admin/payments` once
  `/admin/settlement` replaces it.
- **Verification:** `bank-backend/scripts/verify-two-tier-phase1.sh` runs every §10 row, plus
  the positive paths around them, against a running backend on an empty database.

### Phase 2: Institution-scoped banking

- Statements signed with the institution's key and headed with its name
- Institution unblocks its customers' cards (Q4)
- Customer UI shows the customer's bank
- Institution-code prefixes on account and card numbers (Q3)

**Done when:** a statement verifies against the *institution's* public key, checked outside
the application as before, and a customer's card blocked after three wrong PINs can be
unblocked by their own bank and by no other.

**Implemented.** Notes:

- **Institution-code prefixes needed a numeric code (Q3).** Account numbers are 16 digits and
  card numbers must stay Luhn-checkable, but `institutionCode` is alphanumeric ("ALPHA"), so
  it cannot prefix either. Each institution is therefore also assigned a three-digit
  **bank number** at licensing (unique, 100–999), which is what prefixes the numbers it
  issues — the letter code stays for people to read. This matches how real bank codes and
  card IINs work. An account number is the bank number plus 13 random digits; a card number
  is `4` + the bank number + random digits + the Luhn check digit, still 16 digits.
  The prefix is presentation only: which bank holds an account is still `Account.institution`,
  so nothing routes on it.
- **Statements** are signed with the institution's key (`signingKeyOf(account.getInstitution())`)
  and headed with its name and codes. The envelope's shape is unchanged, only its signer.
  The central-bank emblem is gone; it was also silently broken, since the template referenced
  a relative image that never resolved when the HTML was rendered without a base URL.
- **Card unblocking (Q4) was already delivered in Phase 1**, because §10's negative-test table
  is Phase 1's done-criterion.
- **Verification:** `bank-backend/scripts/verify-two-tier-phase2.sh` downloads a real statement
  PDF, reads its printed values back with `pdftotext`, rebuilds the signed envelope from the
  page alone, and verifies it with Python's `cryptography` against the institution's public
  key — confirming it does *not* verify against the customer's key, and fails on an altered
  balance. `bank-frontend/scripts/verify-two-tier-ui.mjs` covers the UI for both phases.

### Phase 3: Settlement and supervision

- Inter-bank payments as four postings; intra-bank unchanged
- Settlement recipients rejected; net debit cap (Q1)
- Central Bank console reworked around institutions and settlement
- Institution overview shows its own settlement position

**Done when:** invariants I1–I5 hold against the running database after a mixed run of
intra-bank and inter-bank payments, including a concurrent pair of inter-bank payments
from one institution.

**Implemented.** Notes:

- **Four postings, one reference, one transaction** (`AccountService.settleInterBank`), taken
  when the two accounts' institutions differ. Intra-bank payments still write the same two
  postings as before and never touch a settlement account (I5).
- **The net debit cap (Q1) is one configured value** (`app.settlement.net-debit-cap`, default
  5,000,000 TZS) applied to each institution independently, rather than a per-institution
  column. It is genuinely "per institution" in effect; a stored per-bank override would need an
  endpoint for the Central Bank to vary it, which nothing in this plan calls for. That is the
  natural next step if caps should differ by bank.
- **The concurrency case is closed by locking** the paying institution's settlement account
  (`findSettlementForUpdate`, `PESSIMISTIC_WRITE`) before its position is read, so two payments
  leaving one bank at once cannot both be told there is room for one of them. Only the debited
  side is locked — a credited position only rises — which also means two banks paying each
  other simultaneously cannot deadlock.
- **The refusal is split across two columns** (Q1): `failureReason` is what the customer is
  told ("The payment could not be settled", naming no bank and no cause) and `failureDetail`
  is the specific breach, exposed only to that institution and the Central Bank. The
  customer-facing payment response has no detail field at all, rather than relying on a
  mapping to remember to redact it.
- **Added beyond §7.1:** `GET /admin/settlement/refusals` and `GET /institution/payments`,
  without which Q1's "visible to the institution and the Central Bank" would have had nowhere
  to appear. The institution view is the one place the detail sits beside the customer who hit
  it, which §6's visibility table allows a bank for its own customers.
- **Retired:** `GET /admin/payments`, replaced by `GET /admin/settlement` as §7.2 required.
- **Verification:** `bank-backend/scripts/verify-two-tier-phase3.sh` runs a mixed intra/inter
  workload, drives a bank into its cap, fires the concurrent pair, and then checks I1–I5 in SQL
  against the database rather than asking the application whether it is consistent.

---

## 14. Out of Scope for This Plan

Named deliberately, so their absence isn't mistaken for oversight:

- **Institution staff users.** Decision 2 keeps one login per institution; an `Institution`
  entity with multiple staff logins can follow later.
- **Customers banking at more than one institution.** One customer, one account, one
  institution, consistent with the earlier decision not to separate a Party from its login.
- **Reserve funding operations** (the Central Bank crediting an institution's settlement
  account). Positions start at zero and move only through payments, bounded by the net debit
  cap if Q1 is accepted.
- **ISO 20022 `pacs.008`** for the inter-bank leg. It was skipped earlier because the system
  wasn't a clearing participant. This pivot makes the Central Bank a settlement operator, so
  it becomes relevant, but it's a follow-up rather than part of this plan.
- **Payroll disbursement from slips.** A slip's payer and payee could eventually move real
  money between real accounts, which is a natural extension once institutions own accounts.
- KMS/HSM key custody, which remains deferred to the deployment stage.
