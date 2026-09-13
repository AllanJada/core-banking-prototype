-- Phase 3 of the two-tier restructure: inter-bank settlement.
--
-- A payment refused because the paying institution would breach its net debit cap is told to
-- the customer in general terms ("could not be settled") while the specific cause stays
-- visible to that institution and the Central Bank (plan §12, Q1). The two audiences get two
-- columns, rather than one column that some readers have to be trusted to redact.
--
-- Nothing else in this phase needs a schema change: settlement accounts already exist, and an
-- inter-bank payment is four rows in the existing postings table under one transaction_ref.
alter table payments.payments
    add column failure_detail varchar(255);
