# How the System Works

Status: reflects the implementation as it stands (two-tier banking, all three phases, plus the
ISO 20022 `pacs.008` interbank message).

This is the walkthrough document. `README.md` explains each module and why it was built that
way; `ARCHITECTURE.md` cuts across the whole system once and shows its shape;
`TWO_TIER_BANKING_PLAN.md` records the restructure and the decisions behind it. This one
follows the **flows** — what actually happens, step by step, when someone licenses a bank,
opens an account, or pays someone at another institution — and says what each step is *for*.
The last section answers a separate question: if zero-knowledge proofs were added with Noir,
where in this system would they go.

---

## 1. The system in one page

Three tiers, each provisioning the one below it:

```
Central Bank (BANK)                    supervises, licenses, settles
   │  creates → institutions, other overseers
   │  holds → no account of its own
   ▼
Institution (INSTITUTION)              a commercial bank; one login each
   │  creates → its own customers
   │  holds → one settlement account at the Central Bank
   ▼
Customer (NORMAL_USER)                 banks with exactly one institution
      holds → one account, one debit card
```

| Role | Can do | Cannot do |
|---|---|---|
| Central Bank | License institutions, add overseers, read institution aggregates, read settlement positions/movements/refusals, read every interbank message and file transfer | Create a customer, see a customer's identity or balance, move any money |
| Institution | Open and list its own customers, unblock their cards, read its own aggregates and settlement position, read its customers' payments, exchange signed documents with other institutions, read interbank messages it is party to | Touch another bank's customers, messages, or payments |
| Customer | Deposit, pay, request payment by link, hold a card, read their own account/history/statement | See anyone else's account; pay a settlement account; see why settlement failed |

Money only ever exists as **postings** in one append-only ledger. Every balance on every
screen is summed from those postings at the moment it is read.

---

## 2. The rules every flow obeys

These are the reasons the flows below look the way they do. Each one is a property that holds
system-wide, not a feature of a single endpoint.

**1. Balances are derived, never stored.** There is no balance column anywhere. A balance is
`SUM(credits) - SUM(debits)` over an account's postings.
*Significance:* a stored counter can't answer "how did it get to this number", can't be
reconciled against its own history, and carries forward every past bug. Deriving means the
balance and the history can never disagree.

**2. Postings are append-only.** Every column is `updatable = false`. Money moves by writing
new rows, never by editing old ones.
*Significance:* the ledger becomes evidence. Correcting a mistake means a compensating entry
that is itself visible, which is how real books work.

**3. The acting account comes from the token, never the request.** `JwtAuthenticationFilter`
resolves the JWT into an `AuthenticatedUser`, and services scope queries by that id.
*Significance:* there is no parameter to tamper with. The one id that does appear in a path —
an institution naming its own customer — is looked up *within* that institution (rule 7).

**4. Every check runs before anything is written.** Recipient exists, not paying yourself,
under the per-transaction cap, sufficient funds, under the daily cap, within the settlement
cap — then, and only then, sign and post.
*Significance:* by the time money moves, the decision has already been made. There is no
partially-applied payment to unwind.

**5. Generate, sign, persist — with no gap.** Documents are rendered, hashed and signed in the
same call that stores them.
*Significance:* closes the window where what was agreed and what was signed could differ. A
slip altered between composing and signing is a bad document; a payment amount altered in that
window is the wrong money.

**6. Refusals survive the rollback that caused them.** A rejected payment throws, which rolls
back its transaction — so the *record* of the refusal is written by a separate bean in a
`REQUIRES_NEW` transaction (`FailedPaymentRecorder`, `CardPinAttemptRecorder`).
*Significance:* "why didn't that go through" has an answer, and a wrong-PIN counter that reset
on every attempt would never lock a card.

**7. Tenancy lives in the query, not the UI.** An institution's lookups filter by the
institution from its token inside the SQL: `findByOwner_UserIdAndInstitution_UserIdAndType`.
*Significance:* another bank's customer comes back with the same status and message as an id
that was never issued, so nothing can be probed for existence.

**8. Structured messages are schema-validated before they count.** `pain.001` and `pacs.008`
are both generated from the official ISO XSD checked into the repo and validated against that
same file before being signed, stored or sent.
*Significance:* the standard's value is that a receiver may reject a malformed message. A
sender that emits invalid XML anyway has given that up — and, for `pacs.008`, has moved money
on an instruction nobody can act on.

**9. The schema belongs to migrations.** Flyway owns the database (`V1`–`V4`); Hibernate only
validates at startup.
*Significance:* `ddl-auto=update` never revises a constraint once created, which had already
shipped a stale enum `CHECK` constraint. Now an entity change needs a migration beside it.

**10. Everything is verified against a running system.** Four backend suites and a browser
suite, each against an empty database.
*Significance:* the properties above are claims. The scripts are what makes them checkable
rather than aspirational.

---

## 3. The flows

### 3.1 First-time setup — the chain of custody has to start somewhere

```
Anonymous ──POST /api/v1/users {role: BANK}──▶ the first Central Bank overseer
```

1. The sign-in page calls the public `GET /api/v1/users/bootstrap` and learns setup is open.
2. An anonymous `POST /api/v1/users` creates the **first overseer, and only an overseer** — a
   request naming `INSTITUTION` or `NORMAL_USER` is refused outright.
3. The moment that account exists, the window closes permanently. Every later account is
   created by the tier above it.

*Why:* provisioning is a chain of custody — each account is created by an identifiable party
above it — and a chain needs a first link. Restricting the anonymous window to an overseer is
what stops an institution existing before any supervisor does. The check lives in
`UserService`, not a route rule, because it depends on database state rather than the request.

### 3.2 Licensing a bank

```
BANK ──POST /api/v1/admin/institutions──▶ INSTITUTION + its settlement account (one transaction)
```

1. The code is normalised and validated (3–8 letters or digits, unique).
2. A three-digit **bank number** (100–999, unique) is allocated.
3. The login is created with its own Ed25519 key pair.
4. `AccountService.openSettlementAccount` opens the institution's settlement account.

*Why:* steps 3 and 4 share one transaction because an institution without a settlement account
would have nowhere for an inter-bank payment to land, and nothing in the system knows how to
repair that. The bank number exists because account and card numbers must stay all-digits and
Luhn-checkable — an alphanumeric code like `ALPHA` cannot prefix them — and it is presentation
only: which bank holds an account is the `Account.institution` relation, so nothing routes on
those digits.

### 3.3 Opening a customer

```
INSTITUTION ──POST /api/v1/institution/customers──▶ NORMAL_USER + their account (one transaction)
```

1. The institution is taken **from the caller's token**.
2. The customer login is created, with its own key pair.
3. Their account is opened *at that institution*, numbered with the bank's own prefix.

*Why:* the institution never appears in the request body, so a bank cannot open a customer at
another bank by naming it. Login and account commit together, so a customer can never end up
with one and not the other.

### 3.4 Signing in

One login screen for all three roles. `POST /api/v1/auth/login` returns an HS256 JWT carrying
`userId` and `role`; the role decides the landing page and every authorization check
thereafter. Unknown username and wrong password return the same message.

*Why:* the role decides what an account may reach, never which door it came through — the
earlier arrangement of a separate "Bank Login" page decided access by page, which is a UI
detail pretending to be a security boundary. Identical failure messages stop the endpoint being
used to enumerate usernames.

### 3.5 Putting money in

```
Customer ──POST /api/v1/payments/deposits──▶ 1 CREDIT posting + a signed deposit record
```

The per-transaction and daily caps deliberately **do not** apply.

*Why:* those caps exist to bound what can *leave* an account; a deposit only adds. The deposit
is signed with the customer's key over account, amount and timestamp, because the customer is
the party asserting the movement.

### 3.6 Paying inside one bank

```
Customer ──POST /api/v1/payments──▶ checks ──▶ sign ──▶ 2 postings (DEBIT payer, CREDIT payee)
```

1. Amount well-formed (positive, ≤ 2 decimals) — a malformed amount is a broken request, not a
   refused payment, and leaves no record.
2. Recipient resolves to a **customer** account. A settlement account number is refused exactly
   as an unknown number is.
3. Not paying yourself; under the per-transaction cap; sufficient funds (no overdraft, ever);
   under the daily cap.
4. Both institutions compared — same bank here, so the settlement tier is not involved.
5. Sign the payment envelope with the payer's key.
6. `AccountService.transfer` writes both postings under one `transactionRef`, in one
   transaction.

*Why:* pairing the postings inside the ledger rather than in the caller means no code path can
write one side, do something else, and then write the other. Refusing settlement accounts with
the *same* message as an unknown number means a customer cannot use the payments endpoint to
discover which numbers are settlement accounts (invariant I5's companion at the API edge).

### 3.7 Paying across banks — the settlement tier

This is the flow with the most moving parts, and the one the two-tier restructure exists for.

```
Ann (ALPHA) pays Ben (BETA) 50,000

  Ann's account          DEBIT   50,000     ← customer loses it
  ALPHA settlement       DEBIT   50,000     ← ALPHA owes the system
  BETA  settlement       CREDIT  50,000     ← BETA is owed by the system
  Ben's account          CREDIT  50,000     ← customer gains it
                         ───────────────
                         net     0          one transactionRef, one transaction
```

1. All the checks from §3.6 run first.
2. Routing is decided by **comparing the two accounts' institutions** — never by anything the
   payer sends.
3. The paying bank's settlement account is **locked** (`PESSIMISTIC_WRITE`) before its position
   is read.
4. The net debit cap is checked: would this payment take the position below `-cap`?
5. The payment is signed with the customer's key.
6. `AccountService.settleInterBank` writes all four postings.
7. An ISO 20022 **`pacs.008`** is generated, schema-validated, canonicalised, hashed, signed by
   the sending bank, stored encrypted, and recorded — all inside the same transaction.

*Why each of the unusual steps:*

- **Four postings, not two.** Each bank's books must balance on their own: ALPHA's obligation
  to Ann fell by exactly what its settlement position fell by. Two postings would move money
  between banks without either bank's position changing, which is not a thing that can happen.
- **Locking before reading the position.** Two payments leaving one bank at the same instant
  would otherwise both read a position with room for one of them and both be allowed. Only the
  *debited* side is locked — a credited position only rises, so there is nothing to race for —
  which also means two banks paying each other simultaneously cannot deadlock.
- **The cap at all.** A negative position means a bank owes the rest of the system; that is
  normal between settlement runs, but unbounded it lets one bank owe without limit.
- **A generic refusal.** The customer is told only "The payment could not be settled". Naming
  their bank's liquidity to them would leak it, and it is not theirs to fix. The specific cause
  is written to a *separate column* and shown to that bank and to the Central Bank — two
  audiences, two columns, rather than one column some readers must be trusted to redact.
- **The message inside the transaction.** Money and the instruction that moved it commit
  together. A message that fails schema validation takes the payment down with it, rather than
  leaving settled money nobody can evidence.

### 3.8 Pay by link

A customer creates a shareable request; anyone signed in who opens it can pay. The link row is
locked while being paid, expiry is evaluated **at payment time**, and paying resolves to an
ordinary payment through `PaymentService`.

*Why:* because it resolves to an ordinary payment, every rule above applies unchanged — caps,
funds, signing, and inter-bank routing. A link paid by a customer of another bank settles
across the settlement tier automatically, with no code in the link module aware of it.

### 3.9 Cards and PINs

Issue (one per account, number = `4` + bank number + digits + Luhn check digit) → verify PIN →
change PIN → block. Three consecutive wrong PINs block the card; the counter commits in its own
transaction. Only the customer's **own institution** can unblock.

*Why:* the PIN is bcrypt-hashed like a password, so nothing in the system can read one back.
No CVV is stored at all, since PCI DSS forbids retaining it and PIN verification makes it
pointless. Blocking is one-way for the customer because reversing a block is a bank decision —
and until Phase 1 that was documented but impossible, since no role could perform it.

### 3.10 Statements

Computed on request, never stored: opening balance is the sum of every posting *before* the
period, and the closing balance is that total after the period's movements.

The document is **signed with the issuing institution's key**, and headed with its name and
bank code.

*Why:* deriving means two statements for adjoining periods always agree at the boundary, and
regenerating one always gives the same figures. The institution signs because a statement is a
document the *bank* issues about an account — the customer is not the party attesting to it.
The signature and the exact values it covers are printed on the page, so it can be checked with
nothing but the institution's public key and what is on the paper.

### 3.11 Documents between institutions

Compose a slip → server renders the PDF → generates the matching `pain.001` → hashes both →
signs **one combined envelope** over the document, the payload and the UETR → encrypts at rest.
The recipient previews, then approves or rejects; only after approval can it be downloaded.

*Why:* one envelope over both artefacts stops a valid PDF signature and a valid XML signature
from two different transfers being presented as one pair that disagree about the amount.
Approval re-runs the full integrity check rather than trusting an earlier preview, because
approving is a claim about one specific document *now*. Preview is deliberately the one
resilient check — it reports `integrityValid: false` with a reason instead of erroring, since
surfacing exactly that problem is what a review step is for.

### 3.12 Supervision

The Central Bank sees: platform totals; institutions as aggregates (customer count, funds held,
settlement position); settlement positions with their cap and headroom; bank-to-bank movements;
refusals with their causes; interbank messages; file transfers. The zero-sum check on
settlement positions (**I2**) is recomputed on every read, and the console shows an error
banner if it ever fails.

*Why:* supervision is of *institutions*. A movement names two banks and an amount and carries
no customer identity on either side, and the earlier every-account listing was retired for
exactly that reason. Aggregates cross the privacy boundary; individual balances do not.

---

## 4. How all of this is checked

| Suite | What it proves |
|---|---|
| `verify-two-tier-phase1.sh` | Every refusal in the tenancy/provisioning table: cross-bank reads, wrong-tier provisioning, anonymous access, settlement accounts as payment targets |
| `verify-two-tier-phase2.sh` | A statement verifies against the **institution's** key and not the customer's, outside the application; account and card numbers carry the bank's prefix |
| `verify-two-tier-phase3.sh` | Mixed intra/inter workload, the cap, a concurrent pair of payments, and invariants **I1–I5 in SQL against the database** |
| `verify-iso20022-pacs008.sh` | The interbank message validates against the official XSD and its signature verifies, both outside the application; who may read it; tampering refused |
| `verify-two-tier-ui.mjs` | The whole hierarchy built through a real browser, from an empty database |

The invariants themselves:

| ID | Invariant |
|---|---|
| I1 | Every posting effect sums to the total deposited |
| I2 | Settlement positions sum to zero, always |
| I3 | Customer balances sum to the total deposited |
| I4 | Every payment's postings net to zero; every deposit's net to its amount |
| I5 | An intra-bank payment never writes to a settlement account |

---

## 5. What the system does not claim

- **No true non-repudiation.** This server generates and holds every account's private key, so
  a signature proves a document was not altered after this server processed it — not that a
  particular person authorised it. Closing that gap means keys living somewhere this server
  cannot read.
- **Configuration-held secrets.** The JWT secret and the storage master key come from
  configuration; a KMS/HSM is deployment-stage work.
- **No reserve funding, netting or settlement runs.** Positions move only through payments.
- **No KYC/AML, regulatory reporting, or fraud monitoring.**

---

## 6. Where zero-knowledge proofs would fit, if added with Noir

### 6.1 First, the question ZKP actually answers

A zero-knowledge proof is worth its cost only where **one party must convince another of
something, and the second party does not simply trust the first**. Inside a single server that
already holds all the data, a proof adds nothing: the server can just look.

So the question is where this system has genuine *distrust boundaries*. It has three:

| Boundary | What is claimed today | How it is trusted today |
|---|---|---|
| Institution → Central Bank | "My customers hold X in total; my position is Y" | The Central Bank computes it itself from the shared ledger |
| Customer → outside party | "I hold at least X" | A signed PDF statement revealing every transaction in the period |
| Institution → Institution | "This settlement instruction is well-formed" | A signed `pacs.008`, revealing the customers on both sides |

Those three are where Noir circuits would earn their keep. Note the first one is weaker than it
looks *in this deployment*, because one database holds every bank — the Central Bank computing
aggregates itself is possible only because the tiers are not really separate systems. In a real
deployment they would be, and then a proof replaces a trusted report.

### 6.2 The precondition nobody can skip: commitments

A proof is about *committed* data. Without a commitment, a prover can invent inputs and produce
a perfectly valid proof of a false statement — "garbage in, valid proof out". So the first piece
of work is not a circuit at all:

**A `LedgerCommitmentService`**, which for each account and period builds a Merkle tree over
that account's postings, and publishes the root — signed by the institution, exactly the way
statements are signed today.

```
postings (append-only, already immutable)
    └── Merkle tree per account/period
            └── root ──signed by the institution──▶ stored, and shown to the Central Bank
```

This fits the existing design unusually well, because rule 2 already guarantees the underlying
data never changes. A Merkle root over mutable rows would be worthless; over an append-only
ledger it is meaningful.

### 6.3 Where the circuits go, in order of value

**(a) Customer proves solvency without disclosing their account — highest value, lowest risk.**
Today a customer proving they hold 5,000,000 hands over a statement showing every transaction
they made. Instead: the institution signs a commitment to the balance; the customer proves in
zero knowledge that the committed balance clears a threshold.
*Fits at:* `StatementService` (which already computes and signs exactly these figures) gains a
sibling that emits a commitment rather than a document. Verification happens wherever the
outside party is — a browser, another bank — not in this system at all.

**(b) Institution proves cap compliance to the Central Bank — the supervision case.**
An institution proves "my settlement position after this payment stays within my net debit cap"
without revealing the position itself.
*Fits at:* `PaymentService`'s inter-bank branch, where the cap is checked today, and
`AdminService.settlement`, which would verify proofs instead of reading positions directly.

**(c) Private settlement movements — proves the ledger is consistent without showing amounts.**
The Central Bank currently sees bank-to-bank amounts. A circuit could prove the four postings
net to zero and both agents are licensed, with the amount hidden.
*Fits at:* `AccountService.settleInterBank` and the `SettlementMessage` record — the proof
would sit beside the signature, in the same row.

**(d) Client-side PIN or payment authorisation — the one that addresses the stated limitation.**
If a customer's key never left their device, a proof of knowledge (of a PIN, or of the key
authorising a payment) would give the non-repudiation §5 says the system does not have.
*Fits at:* `CardService.verifyPin` and `PaymentService.pay` — but note this is the most
invasive option, because it changes key custody, not just verification.

### 6.4 What the circuits actually look like

Noir is Rust-shaped; `pub` marks a public input, everything else is private.

```rust
// (a) proof of funds: "the balance the bank committed to is at least `threshold`"
fn main(
    balance: Field,          // private — never leaves the prover
    salt: Field,             // private — blinds the commitment
    commitment: pub Field,   // public — what the institution signed
    threshold: pub Field,    // public — what the verifier asked
) {
    assert(std::hash::pedersen_hash([balance, salt]) == commitment);
    assert(balance as u64 >= threshold as u64);
}
```

```rust
// (b) cap compliance: "position - amount >= -cap", with position hidden
fn main(
    position_offset: Field,   // private — position + OFFSET, kept non-negative
    salt: Field,
    commitment: pub Field,
    cap: pub Field,
    amount: pub Field,
    offset: pub Field,
) {
    assert(std::hash::pedersen_hash([position_offset, salt]) == commitment);
    // Field elements have no signed comparison, so a negative position is carried as an
    // offset. Getting this encoding wrong is the classic way such a circuit silently
    // accepts what it should reject.
    assert(position_offset as u64 + cap as u64 >= amount as u64 + offset as u64);
}
```

```rust
// supporting: "this posting is in the committed ledger"
fn main(posting_hash: Field, index: Field, path: [Field; 20], root: pub Field) {
    assert(std::merkle::compute_merkle_root(posting_hash, index, path) == root);
}
```

### 6.5 Running Noir from a Spring Boot system

There is no JVM-native Noir prover or verifier; proving and verifying run through `nargo` and
Barretenberg (`bb`), or `noir_js` in a browser. Three options:

1. **A verifier sidecar** — a small service wrapping `bb verify`, called over HTTP.
   `ProofVerificationService` in Java sends `{circuit, proof, publicInputs}` and gets back a
   boolean. **This is the option that fits this codebase**, because there is already a
   precedent for it: `PdfGenerationService` drives a headless Chromium through Playwright for
   exactly the same reason — a specialised engine the JVM has no business reimplementing.
2. **On-chain verification** — Noir emits a Solidity verifier. Only worth it if settlement is
   actually moving to a chain; otherwise it adds a blockchain to avoid an HTTP call.
3. **JNI to Barretenberg** — fastest, and the most native-build pain for the least benefit here.

The result would be stored the way signature verification already is — `FileTransfer` carries
`signature` and `signatureValid` side by side, and a proof would carry `proof`,
`publicInputs` and `proofValid` in the same shape, with a `ProofChip` in the UI mirroring the
existing `SignatureChip`.

### 6.6 What it would not fix

- **It does not make the data true.** A proof binds to a commitment; if the party that signs
  the commitment is also the party that could lie about it, the proof only moves the trust, it
  does not remove it. Commitments need an accountable signer and somewhere tamper-evident to
  live.
- **It does not replace I1–I5.** Those check that the ledger is internally consistent. A proof
  says "I know values matching this commitment"; it says nothing about whether the commitment
  described the whole ledger.
- **It does not, by itself, give non-repudiation.** Only option (d) does, and only because it
  moves the key — the cryptography is incidental to that.
- **It adds real cost**: circuit code is code that can be wrong (the offset encoding above is a
  live example), proving takes time on the prover's machine, and the toolchain becomes a
  deployment dependency.

### 6.7 A sensible order

1. **Commitments first** (`LedgerCommitmentService` + signed roots). No circuits yet, and
   useful on its own as tamper-evidence over the ledger.
2. **Proof of funds (a)**, because the verifier is outside the system, so nothing internal has
   to change and the privacy win is immediate and explainable.
3. **Cap compliance (b)**, which is the first case where this system is the verifier and needs
   the sidecar.
4. **Private settlement (c)** and **client-side authorisation (d)** only if the tiers are ever
   genuinely separated — which is the deployment where all of this stops being a demonstration
   and starts being necessary.

*This also picks up the thread left by the earlier post-quantum research: zero-knowledge proofs
were on that roadmap and were deprioritised when the project pivoted to core banking breadth.
The two-tier restructure is what created the distrust boundaries that make them worth building
against.*
