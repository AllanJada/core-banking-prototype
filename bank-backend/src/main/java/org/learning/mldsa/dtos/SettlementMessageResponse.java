package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An ISO 20022 interbank message, as its metadata is listed.
 *
 * The message itself is downloaded separately, so that serving it can re-verify its hash and
 * signature first — a list would otherwise have to either verify every row or imply a
 * guarantee it had not checked.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SettlementMessageResponse {
    private Long messageId;
    private Long paymentId;
    /** Also the ledger's transaction reference for the payment's four postings. */
    private String uetr;
    private String messageType;
    private String debtorAgentName;
    private String debtorAgentCode;
    private String creditorAgentName;
    private String creditorAgentCode;
    private BigDecimal amount;
    private String currency;
    /** SHA-384 of the canonicalised XML, as signed. */
    private String xmlHash;
    private Instant createdAt;
    /**
     * SENT or RECEIVED from the asking institution's point of view; null for the Central
     * Bank, which is a party to neither side.
     */
    private String direction;
}
