-- Phase 2 of the two-tier restructure: every institution gets a numeric bank number, which
-- prefixes the account and card numbers it issues (plan §12, Q3).
--
-- Nullable, because only INSTITUTION rows carry one — the same shape as institution_code.
-- There is deliberately no backfill: an institution licensed before this migration has no
-- number, and provisioning refuses to issue an account for it rather than inventing a prefix
-- that would claim to be some other bank. Such an institution is re-licensed, not patched.
alter table identity.users
    add column institution_number varchar(3);

alter table identity.users
    add constraint users_institution_number_key unique (institution_number);
