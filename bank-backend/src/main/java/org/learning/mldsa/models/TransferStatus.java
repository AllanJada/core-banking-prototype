package org.learning.mldsa.models;

/**
 * The lifecycle of a file transfer, from the recipient's side.
 *
 * SENT is the only state a transfer can be picked up (downloaded) from indirectly — a
 * recipient must move it to APPROVED first, and the review module is what makes that
 * decision explicit rather than implicit in the act of downloading. Previewing the document
 * and its ISO 20022 payload is allowed at any status; only the final pickup (download) is
 * gated.
 */
public enum TransferStatus {

    /** Sent and awaiting the recipient's review decision. Previewable, not yet downloadable. */
    SENT,

    /** Reviewed and accepted by the recipient. Now downloadable. */
    APPROVED,

    /** Reviewed and refused by the recipient, with a reason. Terminal — never downloadable. */
    REJECTED,

    /** Picked up at least once, after having been approved. Terminal on the happy path. */
    DOWNLOADED
}
