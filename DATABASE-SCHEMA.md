# Database Schema

Status: reflects the live schema after migrations `V1`–`V5`, dumped from a migrated database
rather than written by hand.

One PostgreSQL database, five schemas — one per owning module — plus Flyway's own history table
in `public`. Nine tables in total.

**Flyway owns this schema.** The migrations in `bank-backend/src/main/resources/db/migration`
are the source of truth, and Hibernate runs with `ddl-auto=validate`, so it checks the entities
against the migrated schema at startup and changes nothing. The consolidated script in §4 is a
convenience snapshot of what those migrations produce — see §5 before running it.

---

## 1. Layout

| Schema | Tables | Owning module |
|---|---|---|
| `identity` | `users` | Identity & Access |
| `ledger` | `accounts`, `postings` | Ledger |
| `payments` | `deposits`, `payments`, `payment_links`, `settlement_messages`, `idempotency_keys` | Payments & Settlement |
| `cards` | `debit_cards` | Card Services |
| `filetransfer` | `file_transfers` | File Transfer & Review |
| `public` | `flyway_schema_history` | Flyway |

```
identity.users ──┬── owner_id ──────────▶ ledger.accounts ──┬──▶ ledger.postings
                 ├── institution_id ────▶                   ├──▶ cards.debit_cards
                 │                                          ├──▶ payments.deposits
                 ├── sender_id ─────┐                        ├──▶ payments.payments ──┐
                 ├── receiver_id ───┴──▶ filetransfer.       └──▶ payments.            │
                 │                       file_transfers           payment_links ◀─────┘
                 ├── debtor_agent_id ───┐
                 └── creditor_agent_id ─┴─▶ payments.settlement_messages ──▶ payments.payments
```

Two things worth noticing in that diagram:

- **`ledger.accounts` has two foreign keys into `users`.** `owner_id` is who holds the account;
  `institution_id` is which bank it is held at. For a customer account they differ; for a
  settlement account they are the same institution.
- **There is no balance column anywhere.** A balance is derived by summing `ledger.postings`.
  This is the decision the rest of the schema follows from.

---

## 2. Table reference

### `identity.users`

Every login in the system, of all three roles.

| Column | Type | Notes |
|---|---|---|
| `user_id` | `bigint` identity | PK |
| `user_name` | `varchar(255)` not null | Unique — the login name |
| `password` | `varchar(255)` | bcrypt hash |
| `public_key` / `private_key` | `varchar(255)` | Ed25519, Base64. Both halves held server-side — the stated non-repudiation limit |
| `role` | `varchar(255)` not null | `NORMAL_USER` \| `INSTITUTION` \| `BANK` |
| `bic` | `varchar(11)` | ISO 9362, null for every institution here |
| `institution_code` | `varchar(8)` | Unique. `INSTITUTION` rows only — the letter code people read, e.g. `ALPHA` |
| `institution_number` | `varchar(3)` | Unique. `INSTITUTION` rows only — the digits that prefix the account and card numbers it issues |

*Why two institution identifiers:* account numbers are 16 digits and card numbers must stay
Luhn-checkable, so an alphanumeric code cannot prefix either. The numeric code exists for that;
the letter code is for display.

### `ledger.accounts`

| Column | Type | Notes |
|---|---|---|
| `account_id` | `bigint` identity | PK |
| `account_number` | `varchar(16)` not null | Unique. First three digits are the institution's bank number |
| `owner_id` | `bigint` not null | → `identity.users` |
| `account_type` | `varchar(255)` not null | `CUSTOMER` \| `SETTLEMENT` |
| `institution_id` | `bigint` not null | → `identity.users`. The bank holding it |
| `currency` | `varchar(3)` not null | Single-currency deployment, but modelled per account |
| `opened_at` | `timestamptz` not null | |

*Why `institution_id` rather than trusting the number prefix:* routing decisions (is this
payment inter-bank?) read this column. The prefix is presentation, and a presentation detail
should never decide where money goes.

### `ledger.postings`

The only place money exists. Append-only — the application sets every column `updatable = false`.

| Column | Type | Notes |
|---|---|---|
| `posting_id` | `bigint` identity | PK |
| `account_id` | `bigint` not null | → `ledger.accounts` |
| `direction` | `varchar(255)` not null | `CREDIT` \| `DEBIT` — carries the sign |
| `amount` | `numeric(19,2)` not null | Always positive |
| `description` | `varchar(255)` | |
| `transaction_ref` | `varchar(36)` not null | Ties the postings of one operation together: 2 rows for a payment within a bank or a deposit, 4 across banks |
| `posted_at` | `timestamptz` not null | |

*Why the sign lives in `direction`:* a malformed row cannot silently reverse a transaction's
meaning by having a negative amount misread.

### `payments.deposits`

| Column | Type | Notes |
|---|---|---|
| `deposit_id` | `bigint` identity | PK |
| `account_id` | `bigint` not null | → `ledger.accounts`, the customer credited |
| `institution_id` | `bigint` | → `identity.users`, the institution that took it. Null only for deposits predating tellers |
| `amount` | `numeric(19,2)` not null | |
| `description` | `varchar(255)` | |
| `transaction_ref` | `varchar(36)` not null | Joins to **both** postings: the customer's credit and the till's debit |
| `signature` | `varchar(255)` not null | Ed25519 over account, institution code, amount, timestamp — signed by the institution |
| `deposited_at` | `timestamptz` not null | |

### `payments.payments`

Both successful and refused attempts — a refusal is exactly what a customer asks about later.

| Column | Type | Notes |
|---|---|---|
| `payment_id` | `bigint` identity | PK |
| `from_account_id` | `bigint` not null | → `ledger.accounts` |
| `to_account_id` | `bigint` | Null when the recipient could not be resolved |
| `amount` | `numeric(19,2)` not null | |
| `description` | `varchar(255)` | |
| `status` | `varchar(255)` not null | `COMPLETED` \| `FAILED` |
| `failure_reason` | `varchar(255)` | What the **customer** was told |
| `failure_detail` | `varchar(255)` | The specific cause, for the institution and Central Bank only |
| `transaction_ref` | `varchar(36)` | Null on a failure: no postings were written |
| `signature` | `varchar(255)` | Null on a failure: nothing that did not happen is signed |
| `created_at` | `timestamptz` not null | |

*Why two failure columns:* a settlement refusal has two audiences. The customer is told only
that it could not be settled; naming their bank's liquidity to them would leak it. Two columns
means no reader has to be trusted to redact.

### `payments.payment_links`

| Column | Type | Notes |
|---|---|---|
| `id` | `bigint` identity | PK |
| `link_id` | `varchar(36)` not null | Unique — the shareable token |
| `requester_account_id` | `bigint` not null | → `ledger.accounts` |
| `amount` | `numeric(19,2)` not null | |
| `description` | `varchar(255)` | |
| `status` | `varchar(255)` not null | `PENDING` \| `PAID` \| `CANCELLED` \| `EXPIRED` |
| `payment_id` | `bigint` | → `payments.payments`, set once paid |
| `expires_at` / `paid_at` / `created_at` | `timestamptz` | |

*Note:* `EXPIRED` is stored in the enum but derived on read — a link past its deadline reports
expired without a job having swept it.

### `payments.settlement_messages`

One ISO 20022 `pacs.008` per inter-bank payment. None for a payment inside one bank.

| Column | Type | Notes |
|---|---|---|
| `message_id` | `bigint` identity | PK |
| `payment_id` | `bigint` not null | Unique — one message per payment. → `payments.payments` |
| `debtor_agent_id` / `creditor_agent_id` | `bigint` not null | → `identity.users`, the two banks |
| `debtor_agent_code` / `creditor_agent_code` | `varchar(8)` not null | Copied at signing time |
| `uetr` | `varchar(36)` not null | Unique. The same value as the payment's `transaction_ref` |
| `message_type` | `varchar(20)` not null | `pacs.008.001.08` |
| `amount` | `numeric(19,2)` not null | |
| `currency` | `varchar(3)` not null | |
| `stored_filename` | `varchar(255)` not null | Unique. The encrypted XML on disk |
| `xml_hash` | `varchar(255)` not null | SHA-384 of the canonicalised XML |
| `signature` | `varchar(255)` not null | Ed25519 by the sending bank |
| `created_at` | `timestamptz` not null | |

*Why the agent codes are duplicated here* even though they can be joined from `users`: the
signed envelope is rebuilt from these values. Persisting what was signed means a later change
to an institution cannot silently invalidate — or silently repair — an old signature.

### `cards.debit_cards`

| Column | Type | Notes |
|---|---|---|
| `card_id` | `bigint` identity | PK |
| `card_number` | `varchar(16)` not null | Unique. `4` + bank number + digits + Luhn check digit |
| `account_id` | `bigint` not null | → `ledger.accounts` |
| `expires_on` | `date` not null | |
| `status` | `varchar(255)` not null | `ACTIVE` \| `BLOCKED` \| `EXPIRED` (expiry derived on read) |
| `pin_hash` | `varchar(255)` not null | bcrypt. No plaintext PIN, ever. No CVV column at all |
| `failed_pin_attempts` | `integer` not null | Three strikes blocks the card |
| `issued_at` | `timestamptz` not null | |

### `filetransfer.file_transfers`

| Column | Type | Notes |
|---|---|---|
| `transfer_id` | `bigint` identity | PK |
| `sender_id` / `receiver_id` | `bigint` not null | → `identity.users`, both institutions |
| `original_filename` | `varchar(255)` not null | Display only — never used as a path |
| `stored_filename` | `varchar(255)` not null | Unique. Server-generated UUID name |
| `stored_xml_filename` | `varchar(255)` | Unique. The `pain.001` payload, when there is one |
| `file_hash` / `xml_hash` | `varchar(255)` | SHA-384 |
| `signature` | `varchar(255)` | Covers both artefacts when a payload exists |
| `signature_valid` | `boolean` | Last verification result |
| `uetr` | `varchar(36)` | Minted once, never regenerated |
| `status` | `varchar(255)` not null | `SENT` → `APPROVED` \| `REJECTED` → `DOWNLOADED` |
| `sent_at` / `downloaded_at` / `reviewed_at` | `timestamptz` | |
| `rejection_reason` | `text` | |
| `payment_id` | `bigint` | Unique. → `payments.payments`. The payment that disbursed this slip's instruction when it was approved |

*Why `payment_id` is unique:* a transfer is decided once, so it can never have disbursed twice.
The constraint enforces that even if the row lock taken at approval were ever removed. Null for
a plain file upload, which carries no instruction, and for anything still awaiting review.

### A note on the `CHECK` constraints

Every enum column is `varchar` with a `CHECK` listing its permitted values. **Adding a value to
a Java enum therefore requires a migration that revises the constraint.** This is not
theoretical: extending `TransferStatus` from two values to four under the old
`ddl-auto=update` setup left a stale constraint rejecting the new values, which is part of why
Flyway was adopted.

---

## 3. Indexes

Primary keys and unique constraints create their own indexes. These are the extra ones, added
because PostgreSQL does not index a foreign key by itself:

| Index | Covers |
|---|---|
| `postings_account_idx` | `ledger.postings (account_id)` — every balance sums by account |
| `accounts_owner_idx` | `ledger.accounts (owner_id)` — every request resolves the caller's own account |
| `accounts_institution_type_idx` | `ledger.accounts (institution_id, account_type)` — every institution-scoped query |
| `accounts_owner_type_key` | `ledger.accounts (owner_id, account_type)` unique — one account per owner per type |
| `settlement_messages_debtor_agent_idx` | `payments.settlement_messages (debtor_agent_id)` |
| `settlement_messages_creditor_agent_idx` | `payments.settlement_messages (creditor_agent_id)` |

---

## 4. Creating it — the complete DDL

Equivalent to migrations `V1`–`V4` applied in order. Tables are created in dependency order, so
the script runs top to bottom against an empty database.

```sql
-- ---------------------------------------------------------------------------------------
-- Database (run from a superuser connection, e.g. psql -U postgres)
-- ---------------------------------------------------------------------------------------
CREATE DATABASE mldsa;
\connect mldsa

-- ---------------------------------------------------------------------------------------
-- Schemas: one per owning module
-- ---------------------------------------------------------------------------------------
CREATE SCHEMA identity;
CREATE SCHEMA ledger;
CREATE SCHEMA payments;
CREATE SCHEMA cards;
CREATE SCHEMA filetransfer;

-- ---------------------------------------------------------------------------------------
-- Identity & Access
-- ---------------------------------------------------------------------------------------
CREATE TABLE identity.users (
    user_id            bigint GENERATED BY DEFAULT AS IDENTITY,
    user_name          varchar(255) NOT NULL,
    password           varchar(255),
    public_key         varchar(255),
    private_key        varchar(255),
    role               varchar(255) NOT NULL,
    bic                varchar(11),
    institution_code   varchar(8),
    institution_number varchar(3),
    CONSTRAINT users_pkey PRIMARY KEY (user_id),
    CONSTRAINT users_user_name_key UNIQUE (user_name),
    CONSTRAINT users_institution_code_key UNIQUE (institution_code),
    CONSTRAINT users_institution_number_key UNIQUE (institution_number),
    CONSTRAINT users_role_check CHECK (role IN ('NORMAL_USER', 'INSTITUTION', 'BANK'))
);

-- ---------------------------------------------------------------------------------------
-- Ledger. Note the absence of a balance column: a balance is summed from postings.
-- ---------------------------------------------------------------------------------------
CREATE TABLE ledger.accounts (
    account_id     bigint GENERATED BY DEFAULT AS IDENTITY,
    account_number varchar(16) NOT NULL,
    owner_id       bigint NOT NULL,
    account_type   varchar(255) NOT NULL,
    institution_id bigint NOT NULL,
    currency       varchar(3) NOT NULL,
    opened_at      timestamp(6) with time zone NOT NULL,
    CONSTRAINT accounts_pkey PRIMARY KEY (account_id),
    CONSTRAINT accounts_account_number_key UNIQUE (account_number),
    CONSTRAINT accounts_account_type_check CHECK (account_type IN ('CUSTOMER', 'SETTLEMENT', 'CASH')),
    CONSTRAINT accounts_owner_fk FOREIGN KEY (owner_id) REFERENCES identity.users (user_id),
    CONSTRAINT accounts_institution_fk FOREIGN KEY (institution_id) REFERENCES identity.users (user_id)
);

CREATE TABLE ledger.postings (
    posting_id      bigint GENERATED BY DEFAULT AS IDENTITY,
    account_id      bigint NOT NULL,
    direction       varchar(255) NOT NULL,
    amount          numeric(19, 2) NOT NULL,
    description     varchar(255),
    transaction_ref varchar(36) NOT NULL,
    posted_at       timestamp(6) with time zone NOT NULL,
    CONSTRAINT postings_pkey PRIMARY KEY (posting_id),
    CONSTRAINT postings_direction_check CHECK (direction IN ('CREDIT', 'DEBIT')),
    CONSTRAINT postings_account_fk FOREIGN KEY (account_id) REFERENCES ledger.accounts (account_id)
);

-- ---------------------------------------------------------------------------------------
-- Payments & Settlement
-- ---------------------------------------------------------------------------------------
CREATE TABLE payments.deposits (
    deposit_id      bigint GENERATED BY DEFAULT AS IDENTITY,
    account_id      bigint NOT NULL,
    amount          numeric(19, 2) NOT NULL,
    description     varchar(255),
    transaction_ref varchar(36) NOT NULL,
    signature       varchar(255) NOT NULL,
    deposited_at    timestamp(6) with time zone NOT NULL,
    -- Last because V6 adds it with ALTER TABLE, and this DDL reproduces the migrated schema
    -- exactly. Nullable: deposits taken before this was a teller operation were taken by nobody.
    institution_id  bigint,
    CONSTRAINT deposits_pkey PRIMARY KEY (deposit_id),
    CONSTRAINT deposits_account_fk FOREIGN KEY (account_id) REFERENCES ledger.accounts (account_id),
    CONSTRAINT deposits_institution_fk FOREIGN KEY (institution_id) REFERENCES identity.users (user_id)
);

CREATE TABLE payments.payments (
    payment_id      bigint GENERATED BY DEFAULT AS IDENTITY,
    from_account_id bigint NOT NULL,
    to_account_id   bigint,
    amount          numeric(19, 2) NOT NULL,
    description     varchar(255),
    status          varchar(255) NOT NULL,
    failure_reason  varchar(255),
    transaction_ref varchar(36),
    signature       varchar(255),
    created_at      timestamp(6) with time zone NOT NULL,
    failure_detail  varchar(255),
    CONSTRAINT payments_pkey PRIMARY KEY (payment_id),
    CONSTRAINT payments_status_check CHECK (status IN ('COMPLETED', 'FAILED')),
    CONSTRAINT payments_from_account_fk FOREIGN KEY (from_account_id) REFERENCES ledger.accounts (account_id),
    CONSTRAINT payments_to_account_fk FOREIGN KEY (to_account_id) REFERENCES ledger.accounts (account_id)
);

CREATE TABLE payments.payment_links (
    id                   bigint GENERATED BY DEFAULT AS IDENTITY,
    link_id              varchar(36) NOT NULL,
    requester_account_id bigint NOT NULL,
    amount               numeric(19, 2) NOT NULL,
    description          varchar(255),
    status               varchar(255) NOT NULL,
    payment_id           bigint,
    expires_at           timestamp(6) with time zone NOT NULL,
    paid_at              timestamp(6) with time zone,
    created_at           timestamp(6) with time zone NOT NULL,
    CONSTRAINT payment_links_pkey PRIMARY KEY (id),
    CONSTRAINT payment_links_link_id_key UNIQUE (link_id),
    CONSTRAINT payment_links_status_check CHECK (status IN ('PENDING', 'PAID', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT payment_links_requester_account_fk FOREIGN KEY (requester_account_id) REFERENCES ledger.accounts (account_id),
    CONSTRAINT payment_links_payment_fk FOREIGN KEY (payment_id) REFERENCES payments.payments (payment_id)
);

CREATE TABLE payments.settlement_messages (
    message_id          bigint GENERATED BY DEFAULT AS IDENTITY,
    payment_id          bigint NOT NULL,
    debtor_agent_id     bigint NOT NULL,
    creditor_agent_id   bigint NOT NULL,
    debtor_agent_code   varchar(8) NOT NULL,
    creditor_agent_code varchar(8) NOT NULL,
    uetr                varchar(36) NOT NULL,
    message_type        varchar(20) NOT NULL,
    amount              numeric(19, 2) NOT NULL,
    currency            varchar(3) NOT NULL,
    stored_filename     varchar(255) NOT NULL,
    xml_hash            varchar(255) NOT NULL,
    signature           varchar(255) NOT NULL,
    created_at          timestamp(6) with time zone NOT NULL,
    CONSTRAINT settlement_messages_pkey PRIMARY KEY (message_id),
    CONSTRAINT settlement_messages_payment_key UNIQUE (payment_id),
    CONSTRAINT settlement_messages_uetr_key UNIQUE (uetr),
    CONSTRAINT settlement_messages_stored_filename_key UNIQUE (stored_filename),
    CONSTRAINT settlement_messages_payment_fk FOREIGN KEY (payment_id) REFERENCES payments.payments (payment_id),
    CONSTRAINT settlement_messages_debtor_agent_fk FOREIGN KEY (debtor_agent_id) REFERENCES identity.users (user_id),
    CONSTRAINT settlement_messages_creditor_agent_fk FOREIGN KEY (creditor_agent_id) REFERENCES identity.users (user_id)
);

-- A retried request is answered from here rather than executed again. The unique index is the
-- whole mechanism: claiming a key is an insert that either succeeds or violates it, so two
-- identical requests arriving together cannot both find the key unclaimed.
CREATE TABLE payments.idempotency_keys (
    idempotency_id  bigint GENERATED BY DEFAULT AS IDENTITY,
    caller_id       bigint NOT NULL,
    idempotency_key varchar(120) NOT NULL,
    request_method  varchar(8) NOT NULL,
    request_path    varchar(255) NOT NULL,
    -- SHA-256 of the body, so a key reused for a different request is refused rather than
    -- answered with the first request's response.
    request_hash    varchar(64) NOT NULL,
    -- Null while the guarded request is still running.
    response_status integer,
    response_body   text,
    created_at      timestamp(6) with time zone NOT NULL,
    completed_at    timestamp(6) with time zone,
    CONSTRAINT idempotency_keys_pkey PRIMARY KEY (idempotency_id),
    CONSTRAINT idempotency_keys_caller_fk FOREIGN KEY (caller_id) REFERENCES identity.users (user_id)
);

CREATE UNIQUE INDEX idempotency_keys_caller_key ON payments.idempotency_keys (caller_id, idempotency_key);
CREATE INDEX idempotency_keys_created_idx ON payments.idempotency_keys (created_at);

-- ---------------------------------------------------------------------------------------
-- Card Services. No CVV column exists, deliberately.
-- ---------------------------------------------------------------------------------------
CREATE TABLE cards.debit_cards (
    card_id             bigint GENERATED BY DEFAULT AS IDENTITY,
    card_number         varchar(16) NOT NULL,
    account_id          bigint NOT NULL,
    expires_on          date NOT NULL,
    status              varchar(255) NOT NULL,
    pin_hash            varchar(255) NOT NULL,
    failed_pin_attempts integer NOT NULL,
    issued_at           timestamp(6) with time zone NOT NULL,
    CONSTRAINT debit_cards_pkey PRIMARY KEY (card_id),
    CONSTRAINT debit_cards_card_number_key UNIQUE (card_number),
    CONSTRAINT debit_cards_status_check CHECK (status IN ('ACTIVE', 'BLOCKED', 'EXPIRED')),
    CONSTRAINT debit_cards_account_fk FOREIGN KEY (account_id) REFERENCES ledger.accounts (account_id)
);

-- ---------------------------------------------------------------------------------------
-- File Transfer & Review
-- ---------------------------------------------------------------------------------------
CREATE TABLE filetransfer.file_transfers (
    transfer_id         bigint GENERATED BY DEFAULT AS IDENTITY,
    sender_id           bigint NOT NULL,
    receiver_id         bigint NOT NULL,
    original_filename   varchar(255) NOT NULL,
    stored_filename     varchar(255) NOT NULL,
    stored_xml_filename varchar(255),
    file_hash           varchar(255),
    xml_hash            varchar(255),
    signature           varchar(255),
    signature_valid     boolean,
    uetr                varchar(36),
    status              varchar(255) NOT NULL,
    sent_at             timestamp(6) with time zone NOT NULL,
    downloaded_at       timestamp(6) with time zone,
    reviewed_at         timestamp(6) with time zone,
    rejection_reason    text,
    payment_id          bigint,
    CONSTRAINT file_transfers_pkey PRIMARY KEY (transfer_id),
    CONSTRAINT file_transfers_stored_filename_key UNIQUE (stored_filename),
    CONSTRAINT file_transfers_stored_xml_filename_key UNIQUE (stored_xml_filename),
    CONSTRAINT file_transfers_payment_key UNIQUE (payment_id),
    CONSTRAINT file_transfers_status_check CHECK (status IN ('SENT', 'APPROVED', 'REJECTED', 'DOWNLOADED')),
    CONSTRAINT file_transfers_sender_fk FOREIGN KEY (sender_id) REFERENCES identity.users (user_id),
    CONSTRAINT file_transfers_receiver_fk FOREIGN KEY (receiver_id) REFERENCES identity.users (user_id),
    CONSTRAINT file_transfers_payment_fk FOREIGN KEY (payment_id) REFERENCES payments.payments (payment_id)
);

-- ---------------------------------------------------------------------------------------
-- Indexes. PostgreSQL does not index a foreign key by itself.
-- ---------------------------------------------------------------------------------------
CREATE INDEX postings_account_idx ON ledger.postings (account_id);
CREATE INDEX accounts_owner_idx ON ledger.accounts (owner_id);
CREATE INDEX accounts_institution_type_idx ON ledger.accounts (institution_id, account_type);
-- An owner may hold more than one account — an institution has both a settlement position
-- and a till — but never two of the same type.
CREATE UNIQUE INDEX accounts_owner_type_key ON ledger.accounts (owner_id, account_type);
CREATE INDEX settlement_messages_debtor_agent_idx ON payments.settlement_messages (debtor_agent_id);
CREATE INDEX settlement_messages_creditor_agent_idx ON payments.settlement_messages (creditor_agent_id);
```

---

## 5. Which way to create a database

**Normally, let the application do it.** Create an empty database and start the app; Flyway
applies `V1`–`V4` and records them in `public.flyway_schema_history`:

```bash
createdb -h localhost -U postgres mldsa
cd bank-backend && mvn spring-boot:run
```

**If you run the script in §4 by hand**, Flyway will then find tables it has no history for and
refuse to start. Tell it the schema is already at V4:

```properties
spring.flyway.baseline-on-migrate=true
spring.flyway.baseline-version=5
```

…or insert the history rows yourself. The script is most useful for reading, for a throwaway
analysis copy, or for creating the schema somewhere Flyway isn't running — not as the normal
path.

**To reset completely** (irreversible — this is what the two-tier restructure did):

```bash
dropdb -h localhost -U postgres mldsa && createdb -h localhost -U postgres mldsa
rm -f bank-backend/storage/files/*          # stored files belong to rows that no longer exist
```

**To clear data but keep the schema and migration history** — what the verification suites do
between runs:

```sql
TRUNCATE identity.users, ledger.accounts, ledger.postings,
         payments.deposits, payments.payments, payments.payment_links,
         payments.settlement_messages, cards.debit_cards,
         filetransfer.file_transfers
RESTART IDENTITY CASCADE;
```

---

## 6. Queries worth having

**One account's balance** — what the application does on every read:

```sql
SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END), 0) AS balance
FROM ledger.postings
WHERE account_id = :account_id;
```

**Every settlement position**, with the bank that holds it:

```sql
SELECT i.institution_code,
       a.account_number,
       COALESCE(SUM(CASE WHEN p.direction = 'CREDIT' THEN p.amount ELSE -p.amount END), 0) AS position
FROM ledger.accounts a
JOIN identity.users i ON i.user_id = a.institution_id
LEFT JOIN ledger.postings p ON p.account_id = a.account_id
WHERE a.account_type = 'SETTLEMENT'
GROUP BY i.institution_code, a.account_number
ORDER BY i.institution_code;
```

**The ledger invariants** (I1–I5), each returning `true` on a healthy database. These are the
checks `verify-two-tier-phase3.sh` runs:

```sql
-- I1  every posting effect sums to the total deposited
SELECT (SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount ELSE -amount END), 0)
        FROM ledger.postings)
     = (SELECT COALESCE(SUM(amount), 0) FROM payments.deposits) AS i1;

-- I2  settlement positions sum to zero, always
SELECT COALESCE(SUM(CASE WHEN p.direction = 'CREDIT' THEN p.amount ELSE -p.amount END), 0) = 0 AS i2
FROM ledger.postings p
JOIN ledger.accounts a ON a.account_id = p.account_id
WHERE a.account_type = 'SETTLEMENT';

-- I3  customer balances sum to the total deposited
SELECT (SELECT COALESCE(SUM(CASE WHEN p.direction = 'CREDIT' THEN p.amount ELSE -p.amount END), 0)
        FROM ledger.postings p
        JOIN ledger.accounts a ON a.account_id = p.account_id
        WHERE a.account_type = 'CUSTOMER')
     = (SELECT COALESCE(SUM(amount), 0) FROM payments.deposits) AS i3;

-- I4a every payment's postings net to zero
SELECT NOT EXISTS (
    SELECT 1 FROM payments.payments pay
    JOIN ledger.postings p ON p.transaction_ref = pay.transaction_ref
    WHERE pay.transaction_ref IS NOT NULL
    GROUP BY pay.transaction_ref
    HAVING SUM(CASE WHEN p.direction = 'CREDIT' THEN p.amount ELSE -p.amount END) <> 0) AS i4a;

-- I4b every deposit's postings net to its amount
SELECT NOT EXISTS (
    SELECT 1 FROM payments.deposits d
    JOIN ledger.postings p ON p.transaction_ref = d.transaction_ref
    GROUP BY d.transaction_ref, d.amount
    HAVING SUM(CASE WHEN p.direction = 'CREDIT' THEN p.amount ELSE -p.amount END) <> d.amount) AS i4b;

-- I5  no intra-bank payment ever touches a settlement account
SELECT NOT EXISTS (
    SELECT 1
    FROM payments.payments pay
    JOIN ledger.accounts fa ON fa.account_id = pay.from_account_id
    JOIN ledger.accounts ta ON ta.account_id = pay.to_account_id
    JOIN ledger.postings p ON p.transaction_ref = pay.transaction_ref
    JOIN ledger.accounts pa ON pa.account_id = p.account_id
    WHERE pay.transaction_ref IS NOT NULL
      AND fa.institution_id = ta.institution_id
      AND pa.account_type = 'SETTLEMENT') AS i5;
```

**Posting count per payment** — two within a bank, four across banks:

```sql
SELECT pay.payment_id,
       fa.institution_id = ta.institution_id AS intra_bank,
       COUNT(p.posting_id) AS postings
FROM payments.payments pay
JOIN ledger.accounts fa ON fa.account_id = pay.from_account_id
JOIN ledger.accounts ta ON ta.account_id = pay.to_account_id
JOIN ledger.postings p ON p.transaction_ref = pay.transaction_ref
WHERE pay.transaction_ref IS NOT NULL
GROUP BY pay.payment_id, intra_bank
ORDER BY pay.payment_id;
```

---

## 7. Changing the schema

1. Add a migration: `bank-backend/src/main/resources/db/migration/V5__what_it_does.sql`.
2. Change the entity to match.
3. Start the app. Flyway applies it; Hibernate's `validate` fails loudly if the two disagree.

Never edit an applied migration — Flyway checksums them and will refuse to start. And remember
that adding a value to an enum means writing the `ALTER … DROP CONSTRAINT … ADD CONSTRAINT`
that widens its `CHECK`; nothing does that automatically.
