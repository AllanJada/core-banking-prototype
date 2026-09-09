package org.learning.mldsa.models;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A payment from one account to another.
 *
 * This is the record of the instruction; the money itself lives in the ledger as the pair
 * of postings sharing this payment's transactionRef. Failed attempts are kept too — a
 * rejected payment is exactly what a customer asks about later, and discarding it would
 * leave "why didn't that go through" unanswerable.
 */
@Entity
@Table(name = "payments", schema = "payments")
@Data
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Long paymentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_account_id", nullable = false, updatable = false)
    private Account fromAccount;

    // Null only on a failed attempt whose recipient could not be resolved — there was no
    // account to point at. Always set once a payment completes.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_account_id", updatable = false)
    private Account toAccount;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "description", updatable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentStatus status;

    /** Why a FAILED payment was rejected; null when it completed. */
    @Column(name = "failure_reason")
    private String failureReason;

    /** Ties this payment to its postings in the ledger. Null on a failed attempt. */
    @Column(name = "transaction_ref", length = 36)
    private String transactionRef;

    // Ed25519 signature over fromAccount|toAccount|amount|timestamp, made with the paying
    // customer's own key. Null on a failed attempt: nothing is signed that did not happen.
    @Column(name = "signature")
    private String signature;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
