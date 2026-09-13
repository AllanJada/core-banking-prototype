package org.learning.mldsa.models;

/**
 * The state of a payment request.
 *
 * EXPIRED is the odd one out: it is never written to the database. Expiry is a function of
 * the clock, so it is derived by comparing expiresAt to now (see PaymentLink.effectiveStatus).
 * Storing it would mean a background job flipping rows at their expiry time, and a link's
 * status would then depend on whether that job had run yet — a link would read as payable
 * for as long as the sweep was late. Deriving it means a link is expired the instant it
 * expires, with nothing to schedule and nothing to fall behind.
 */
public enum PaymentLinkStatus {

    /** Open and payable, provided it has not passed its expiry. */
    PENDING,

    /** Already paid; a link is payable only once. */
    PAID,

    /** Withdrawn by whoever created it. */
    CANCELLED,

    /** Derived, never stored: still PENDING in the database but past its expiry. */
    EXPIRED
}
