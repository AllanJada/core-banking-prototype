package org.learning.mldsa.models;

/**
 * The outcome of a payment attempt.
 *
 * There is deliberately no PENDING value. A payment here settles synchronously — its
 * postings are written in the same transaction that records it — so it either completed
 * or it never moved any money. A pending state would describe asynchronous settlement,
 * which this system does not do, and would suggest money is in flight when it is not.
 */
public enum PaymentStatus {

    /** Money moved: a debit and its matching credit are both in the ledger. */
    COMPLETED,

    /** Rejected before anything was written. Kept as a record of the attempt. */
    FAILED
}
