-- Deposits become a counter operation, funded from an account.
--
-- Until now a deposit wrote one credit posting and nothing else: money entered the ledger
-- from nowhere, and every balance in the system traced back to a customer having credited
-- their own account by whatever amount they typed. This migration gives each institution a
-- till to fund deposits from, so a deposit has two sides like every other movement, and
-- retrofits the same two-sidedness onto the deposits already recorded.

-- 1. The new account type. -----------------------------------------------------------------
-- Postgres CHECK constraints list their values explicitly, so adding an enum constant means
-- replacing the constraint rather than only the Java enum. Dropping and recreating is the
-- whole change: no row can currently hold the new value.
alter table ledger.accounts
    drop constraint accounts_account_type_check;

alter table ledger.accounts
    add constraint accounts_account_type_check
        check (account_type in ('CUSTOMER', 'SETTLEMENT', 'CASH'));

-- An owner may now hold more than one account — an institution has both a settlement position
-- and a till — but never two of the same type. Previously the application enforced one account
-- per owner by looking one up before opening another; that check is now per type, and this
-- constraint is what makes it true rather than merely intended.
create unique index accounts_owner_type_key on ledger.accounts (owner_id, account_type);

-- 2. Who took the deposit. -----------------------------------------------------------------
-- Nullable because deposits taken before this existed were taken by nobody: the account holder
-- credited themselves. Naming an institution for them would assert a bank had received money
-- it never saw.
alter table payments.deposits
    add column institution_id bigint;

alter table payments.deposits
    add constraint deposits_institution_fk foreign key (institution_id) references identity.users;

-- 3. A till for every institution that already exists. --------------------------------------
-- Account numbers follow the same shape the application generates: the institution's three
-- digit number, then digits derived from its id, padded to sixteen. Uniqueness is checked by
-- the table's own constraint — a collision here fails the migration rather than producing two
-- accounts with one number.
insert into ledger.accounts (account_number, owner_id, account_type, institution_id, currency, opened_at)
select
    u.institution_number || lpad((900000000000 + u.user_id)::text, 13, '0'),
    u.user_id,
    'CASH',
    u.user_id,
    coalesce((select a.currency from ledger.accounts a where a.owner_id = u.user_id limit 1), 'TZS'),
    now()
from identity.users u
where u.role = 'INSTITUTION'
  and not exists (
      select 1 from ledger.accounts a
      where a.owner_id = u.user_id and a.account_type = 'CASH');

-- 4. Balance the deposits that were only ever half-written. ---------------------------------
-- Each existing deposit gets the debit posting it never had, against the till of the
-- institution holding the credited account, under the deposit's own transaction_ref. After
-- this every deposit nets to zero like a transfer, and the ledger-wide sum of all postings is
-- zero rather than "whatever was deposited".
--
-- posted_at is the deposit's own timestamp, not now(): the money moved when it was deposited,
-- and dating the other half to the migration would put the two sides of one movement months
-- apart in every statement that spans this point.
insert into ledger.postings (account_id, direction, amount, description, transaction_ref, posted_at)
select
    till.account_id,
    'DEBIT',
    d.amount,
    coalesce(d.description, 'Deposit'),
    d.transaction_ref,
    d.deposited_at
from payments.deposits d
join ledger.accounts customer on customer.account_id = d.account_id
join ledger.accounts till
     on till.owner_id = customer.institution_id
    and till.account_type = 'CASH'
-- Only deposits still missing their debit side, so re-running this migration on a database
-- that already has it cannot double the postings.
where not exists (
    select 1 from ledger.postings p
    where p.transaction_ref = d.transaction_ref
      and p.direction = 'DEBIT');

-- 5. Record who took the backfilled deposits. -----------------------------------------------
-- Deliberately left null. These were self-deposits; the institution now funding them did not
-- take them, and the column says so by being empty.
