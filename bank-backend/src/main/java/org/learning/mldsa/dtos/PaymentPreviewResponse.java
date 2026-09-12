package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What a recipient sees when reviewing a transfer before deciding to approve or reject it.
 *
 * Deliberately resilient rather than throwing: the whole point of a review step is to
 * surface problems so a human can act on them, so a failed integrity check comes back here
 * as {@code integrityValid: false} with a reason, not as a 400 that blocks the review screen
 * from rendering at all. A recipient facing a transfer that no longer verifies needs to see
 * exactly that, so they can reject it with cause.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PaymentPreviewResponse {
    private Long transferId;
    private String senderUsername;
    private String receiverUsername;
    private String originalFilename;
    private String status;
    private Instant sentAt;
    private String uetr;
    private boolean hasPayload;

    /** Whether the document (and payload, if present) still verify against what was signed. */
    private boolean integrityValid;

    /** Set only when integrityValid is false, naming what failed. */
    private String integrityWarning;

    /** Parsed from the ISO 20022 payload; null when there is no payload, or it could not be trusted. */
    private PayloadPreview payload;

    /**
     * The payment instruction's key fields, read out of the pain.001 message rather than off
     * the raw XML — a reviewer should not need to parse ISO 20022 by eye to see who is being
     * paid, how much, and why.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PayloadPreview {
        private String debtorName;
        private String debtorAccountNumber;
        private String creditorName;
        private String creditorAccountNumber;
        private BigDecimal amount;
        private String currency;
        private String executionDate;
        private String remittanceInformation;
    }
}
