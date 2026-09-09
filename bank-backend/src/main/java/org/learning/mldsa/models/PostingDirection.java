package org.learning.mldsa.models;

/**
 * Which way a posting moves money for the account it belongs to.
 *
 * Amounts are always stored positive; this is what gives them their sign when a balance
 * is summed. Keeping it explicit rather than encoding direction as a negative amount
 * means a malformed row can't quietly reverse a transaction's meaning.
 */
public enum PostingDirection {

    /** Money into the account — increases its balance. */
    CREDIT,

    /** Money out of the account — decreases its balance. */
    DEBIT
}
