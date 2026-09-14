-- Payroll disbursement: a slip's ISO 20022 instruction now moves money when the receiving
-- institution approves it, and this column is the link from the document to the payment that
-- carried it out.
--
-- Nullable, because a plain file upload carries no payment instruction and disburses nothing,
-- and because every transfer sent before this existed was never disbursed — there is no
-- payment to point them at, and inventing one would claim money had moved when it had not.
--
-- Unique, because a transfer can only be approved once and so can never have produced two
-- payments. The database enforcing that means a concurrent double-approval fails on the
-- constraint even if the row lock above it were ever removed.
alter table filetransfer.file_transfers
    add column payment_id bigint;

alter table filetransfer.file_transfers
    add constraint file_transfers_payment_key unique (payment_id);

alter table filetransfer.file_transfers
    add constraint file_transfers_payment_fk foreign key (payment_id)
        references payments.payments (payment_id);
