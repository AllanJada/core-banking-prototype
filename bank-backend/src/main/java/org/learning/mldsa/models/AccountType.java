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
    SETTLEMENT,

    /**
     * An institution's till: the account a teller's deposit is funded from, and the point at
     * which money enters the ledger.
     *
     * It exists so that a deposit has two sides. Before it, a deposit wrote a single credit
     * with no counterparty — money appeared, and the ledger's central claim that every
     * movement nets to zero held everywhere except at the one place all the money came from.
     *
     * Its balance runs negative by exactly what the institution has paid into its customers'
     * accounts, which is the point: the amount a bank has put into circulation is now a
     * number on an account someone can look at, rather than an absence of one.
     */
    CASH
}
