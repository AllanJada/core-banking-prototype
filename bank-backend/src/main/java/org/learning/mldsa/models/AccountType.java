package org.learning.mldsa.models;

/**
 * What an account is for, which decides how money may move through it.
 *
 * Held on the account itself rather than inferred from the owner's role, so the ledger can
 * tell customer money from an institution's settlement position without joining to identity
 * — and so a rule like "customers cannot pay a settlement account" is a check on the account
 * being paid, not on who happens to own it.
 */
public enum AccountType {

    /** A retail customer's account at their institution: deposits, payments, a card. */
    CUSTOMER,

    /**
     * An institution's position at the Central Bank. Moves only through inter-bank settlement:
     * never paid by a customer directly, and never deposited into.
     */
    SETTLEMENT
}
