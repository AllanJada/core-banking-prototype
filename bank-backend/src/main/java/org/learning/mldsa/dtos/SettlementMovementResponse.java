package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One movement between two institutions' settlement positions.
 *
 * Bank to bank, amount, time and reference — and deliberately no customer identity or account
 * number on either side. The Central Bank supervises institutions; who paid whom inside them
 * stays with their own banks.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SettlementMovementResponse {
    private Long paymentId;
    /**
     * The ISO 20022 pacs.008 instructing this movement, to fetch it by. Null only for a
     * movement made before interbank messages existed.
     */
    private Long messageId;
    /** Ties this movement to its four postings in the ledger, and to the message's UETR. */
    private String transactionRef;
    private String fromInstitutionName;
    private String fromInstitutionCode;
    private String toInstitutionName;
    private String toInstitutionCode;
    private BigDecimal amount;
    private Instant occurredAt;
}
