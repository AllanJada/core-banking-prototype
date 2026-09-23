# Demo Walkthrough — Credentials & Script

Status: the database has been reset to empty for this demo. Nobody exists yet — you create
every account below, in order, by hand. Nothing in this file has been run by the assistant;
every step is for you to perform.

**Before you start:**
- Frontend: http://127.0.0.1:4173
- Backend: http://localhost:8080
- Password for every account created below: `Passw0rd!` (use it every time you see `<password>`)

If a screen fails to load, both servers are already running — refresh, don't restart anything.

---

## 0. Cast of characters

Three Tanzanian commercial banks, each with two customers, so you can show both an
intra-bank payment (same bank) and an inter-bank payment (different banks) with someone left
over as a control.

| Institution | Code | Customers |
|---|---|---|
| alpha Bank | `alpha` | `juma` (employer), `fatma` |
| beta Bank | `beta` | `hassan` |
| gamma | `gamma` | `zainab` (never touched by the demo — proves tenant isolation) |

You are the **Central Bank overseer** for the first part, then switch into each bank's own
login to show what that bank sees.

---

## 1. First-time setup — become the Central Bank

The sign-in screen detects the empty system and offers first-time setup.

1. Open http://127.0.0.1:5173/login
2. You'll see a banner: *"No Central Bank overseer exists yet."* — click **Set up**
3. Fill in:
   - Username: `boft`
   - Password: `Passw0rd!`
4. Submit. You're told the overseer was created — sign in with those same credentials.

**What to say:** this window only ever exists once. The moment this account is created, nobody
— not even the API directly — can create another Central Bank overseer this way again. Every
account after this one is created by the tier above it.

You should land on the **Central Bank console**.

---

## 2. License the three banks

Still signed in as `boft`. Click **Provision** (top right).

Create each of these one at a time — the dialog defaults to "Institution":

| Institution code | Username | Password |
|---|---|---|
| `alpha` | `alpha-bank` | `Passw0rd!` |
| `beta` | `beta-bank` | `Passw0rd!` |
| `gamma` | `gamma-bank` | `Passw0rd!` |

After each one, note the confirmation message — it names the **settlement account number**
that was opened automatically for that bank.

**What to say while creating alpha:** licensing a bank and opening its settlement account
happen in the same instant, one action — there's no way for a bank to exist without
somewhere for cross-bank money to settle.

Click the **Institutions** tab. You should see all three, each with:
- a three-digit **bank number** (assigned automatically, unique per bank)
- a settlement account number starting with that bank number
- 0 customers, 0 funds, 0 settlement position

**What to say:** that bank number is what will prefix every account number and every card
number these banks issue — the same way a real account number tells you which bank holds it.

Sign out.

---

## 3. Open customers at alpha

Sign in as `alpha-bank` / `Passw0rd!`. You land on alpha's own console, opening on its
**Overview** tab — its own customer count, funds held, and settlement position (all zero).

Click **Provision customer**, create:

| Username | Password |
|---|---|
| `juma` | `Passw0rd!` |
| `fatma` | `Passw0rd!` |

After each, note the account number in the confirmation toast — write down **Juma's account
number** and **Fatma's account number** somewhere, you'll need them shortly.

Click the **Customers** tab. Both should be listed, each with a 16-digit account number
starting with alpha's bank number, zero balance, and no card.

Sign out.

---

## 4. Open a customer at beta

Sign in as `beta-bank` / `Passw0rd!`.

Provision one customer:

| Username | Password |
|---|---|
| `hassan` | `Passw0rd!` |

Write down **Hassan's account number**.

Sign out.

---

## 5. Open a customer at gamma (the control)

Sign in as `gamma-bank` / `Passw0rd!`.

Provision one customer:

| Username | Password |
|---|---|
| `zainab` | `Passw0rd!` |

Write down **Zainab's account number**, but do nothing else with this account — it exists
only so you can show, later, that nobody outside gamma can see or touch it.

Sign out.

---

## 6. Fund Juma's account — at the counter

Money is paid in by the **bank**, not by the customer. Sign in as `alpha-bank` /
`Passw0rd!`, open the **Customers** tab, find Juma's row and click **Deposit** on the right.

Enter **1,000,000** with description "Opening float", submit. The confirmation reads back
Juma's new balance.

**What to say:** notice who is signed in. A customer has no Deposit button anywhere —
if an account holder could credit their own account, they could create money, and every
balance in this system would rest on that. So paying money in is a counter operation, and
it is the *only* way money enters the ledger at all.

**And the part worth showing:** this wrote **two** lines, not one — alpha's own cash
account was debited and Juma credited, under a single transaction reference. The bank's
till now reads −1,000,000: that is the money alpha has put into circulation, and it is a
number on an account rather than money that appeared from nowhere.

Now sign in as `juma` / `Passw0rd!` to see it landed. Note the header: "Your bank — alpha
Bank (alpha)".

**What to say:** the balance shown here is never stored — it's summed from the account's
transaction history on every single load. Refresh the page to show the number doesn't move
on its own and doesn't need "saving".

---

## 7. Intra-bank payment — Juma pays Fatma

Still signed in as `juma`. Click **Send money**:
- To account: **Fatma's account number**
- Amount: **20,000**
- Description: "Lunch"

Submit. Check the transaction history — this payment shows **no** destination-bank chip next
to it, because the money never left alpha.

**What to say:** under the hood this wrote exactly two ledger lines — Juma debited, Fatma
credited — and touched no settlement account anywhere.

Sign out, sign in as `fatma` / `Passw0rd!`, confirm her balance is **20,000**. Sign out.

---

## 8. Inter-bank payment — Juma pays Hassan (alpha → beta)

Sign back in as `juma`. Click **Send money**:
- To account: **Hassan's account number**
- Amount: **50,000**
- Description: "Invoice 42"

Submit. This time the payment row **does** show a bank chip — `beta` — next to the
destination, because this money crossed banks.

**What to say:** this single payment just wrote four ledger lines in one transaction: Juma
debited, alpha's settlement account debited, beta's settlement account credited, Hassan
credited. And it just generated a signed ISO 20022 `pacs.008` message from alpha to beta —
we'll look at that from the bank side in a moment.

Sign out. Sign in as `hassan` / `Passw0rd!`, confirm his balance is **50,000**. Sign out.

---

## 9. See it from the paying bank's side

Sign in as `alpha-bank`. On the **Overview** tab you should now see:
- Customers: 2
- Customer funds held: 980,000 (Juma's 1,000,000 − 20,000 to Fatma stayed inside alpha;
  the 50,000 to Hassan left the bank)
- Settlement position: **−50,000** (alpha now owes the system)
- Headroom: reduced by the same amount

Click **Payments** — you'll see all three payments Juma made, with the cross-bank one
flagged.

Click **Messages** — this is the interbank `pacs.008` alpha sent to beta. Click into it and
download the XML.

**What to say, if opening the XML:** this is a real, ISO 20022–compliant message — the exact
same standard real banks use to instruct each other. It's signed by alpha's own key, and
anyone downloading it gets it re-verified against that signature before it's served — if a
single byte on disk had been tampered with, the download would be refused, not silently
served.

Sign out.

---

## 10. See it from the receiving bank's side

Sign in as `beta-bank`. Overview tab:
- Settlement position: **+50,000** (beta is owed — the mirror image of alpha)

Click **Messages** — the same `pacs.008`, but marked **RECEIVED** rather than SENT.

Sign out.

---

## 11. The Central Bank's supervisory view

Sign in as `boft` again. Click **Institutions** — alpha and beta now show non-zero settlement
positions; gamma still shows zero (nothing has touched it).

Click **Settlement**:
- **Positions** table: alpha at −50,000, beta at +50,000, gamma at 0. They sum to exactly
  **zero** — call this out explicitly, it's a mathematical invariant the system checks after
  every action.
- **Movements** table: one row, alpha → beta, 50,000, with a link to the same `pacs.008`
  message you already saw from both banks' sides.

**What to say:** notice what's *not* here — no customer names, no account numbers. The
Central Bank supervises banks, not individual customers. It can see that alpha owes the
system money and exactly how much, but not who at alpha sent it or who received it.

---

## 12. Prove tenant isolation

Still as `boft`, or sign in as `alpha-bank` — try to find Zainab (gamma's customer) anywhere.
She won't appear in alpha's customer list, and if you have her account number, sending her a
payment from Juma still works (payments cross banks by design) — but alpha has no way to list
her, read her balance, or manage her card. Only `gamma-bank` can do that.

This is the point: every institution-scoped screen is filtered by *who is logged in*, not by
anything the screen asks you to select.

---

## 13. A payslip that actually pays (optional, if time allows)

This is the newest feature — a payroll document that moves real money on approval, not on
send.

1. Sign in as `alpha-bank` (Juma's employer bank). Click **Compose slip**.
2. Fill in a payslip: employer "alpha Test Employer", employee name "Hassan", payer account =
   **Juma's account number**, payee account = **Hassan's account number**, an earnings line
   (e.g. 100,000) and a deductions line (e.g. 10,000), send it to beta (`beta-bank`) as the
   receiver.
3. Note: sending it moves **no money** — check Juma's and Hassan's balances, unchanged.
4. Sign out, sign in as `beta-bank`. Open **Inbox**, review the slip — you'll see the parsed
   payment instruction (net pay = earnings − deductions = 90,000) before deciding.
5. Click **Approve**.
6. Sign out, check Juma's balance (down 90,000) and Hassan's balance (up 90,000, on top of
   the 50,000 from step 8) — the approval is what moved the money, and it moved *exactly*
   the net pay from the signed document, and it settled across banks exactly like step 8
   did, producing another `pacs.008`.

**What to say:** if you reject a slip instead of approving it, nothing moves — rejection has
to stay free, because this system has no "undo" for a payment yet.

---

## 14. Wrap-up talking points

- Every screen you just used is backed by a real PostgreSQL ledger — nothing was mocked for
  this demo.
- The whole hierarchy — overseer, three banks, four customers, deposits, an intra-bank
  payment, an inter-bank payment, and a payslip disbursement — is exactly what the
  automated test suites build and check on every code change, just done here by hand instead
  of by script.
- Everything shown (settlement zero-sum, tenant isolation, signed messages) is a rule the
  system actively enforces, not just a UI convention — try breaking one live if you want:
  e.g. log in as `alpha-bank` and attempt to pay Zainab's account number *to* a settlement
  account number instead of a customer account, or try to reach
  `http://localhost:8080/api/v1/admin/institutions` without a token, and show the refusal.
