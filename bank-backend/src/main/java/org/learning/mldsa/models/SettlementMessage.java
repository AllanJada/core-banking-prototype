package org.learning.mldsa.models;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The ISO 20022 interbank message instructing one institution to pay another — a
 * {@code pacs.008} written for every inter-bank payment.
 *
 * The ledger already records what moved: four postings under one reference. This records what
 * the paying bank *said* to the receiving bank about it, in the message format banks actually
 * exchange, signed so neither side can later present a different one. One message per payment;
 * a payment within a single bank produces none, because nothing crosses an institution.
 *
 * The XML itself lives in encrypted storage like every other document here. What is kept in
 * the row is what the signature was built from — the identifiers, the amount, the two agents'
 * codes and the hash of the message — so the envelope can be rebuilt exactly as it was signed
 * rather than recomputed from values that may since have changed.
 */
@Entity
@Table(name = "settlement_messages", schema = "payments")
@Data
public class SettlementMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Long messageId;

    /** The payment this message instructs. One message per payment, enforced by a unique key. */
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, unique = true, updatable = false)
    private Payment payment;

    /** The paying customer's bank: the instructing agent, and the signer of this message. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "debtor_agent_id", nullable = false, updatable = false)
    private User debtorAgent;

    /** The receiving customer's bank: the instructed agent. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creditor_agent_id", nullable = false, updatable = false)
    private User creditorAgent;

    // The agents' codes as they stood when the message was signed. Held here rather than read
    // back through the relations above, so the signed envelope can be rebuilt from values that
    // cannot have moved underneath it.
    @Column(name = "debtor_agent_code", nullable = false, updatable = false, length = 8)
    private String debtorAgentCode;

    @Column(name = "creditor_agent_code", nullable = false, updatable = false, length = 8)
    private String creditorAgentCode;

    /**
     * The end-to-end reference carried in the message, which is also the ledger's
     * transactionRef for the four postings — so the message and the money it moved are tied
     * by one identifier rather than two that could disagree.
     */
    @Column(name = "uetr", nullable = false, unique = true, updatable = false, length = 36)
    private String uetr;

    /** Which message definition was written, e.g. pacs.008.001.08. */
    @Column(name = "message_type", nullable = false, updatable = false, length = 20)
    private String messageType;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "stored_filename", nullable = false, unique = true, updatable = false)
    private String storedFilename;

    /** SHA-384 of the canonicalised XML — see XmlCanonicalizationService for why canonical. */
    @Column(name = "xml_hash", nullable = false, updatable = false)
    private String xmlHash;

    /** Ed25519 signature by the debtor agent over the envelope covering the fields above. */
    @Column(name = "signature", nullable = false, updatable = false)
    private String signature;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
