package org.learning.mldsa.models;

/**
 * The state of a debit card.
 *
 * As with payment links, EXPIRED is derived from the clock rather than stored — a card is
 * expired the moment its expiry date passes, with no job needed to go and mark it.
 */
public enum CardStatus {

    /** Usable. */
    ACTIVE,

    /** Withdrawn by the customer, or locked after too many wrong PIN attempts. */
    BLOCKED,

    /** Derived, never stored: still ACTIVE in the database but past its expiry date. */
    EXPIRED
}
