package org.learning.mldsa.models;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One movement of money against one account — the ledger of record.
 *
 * Postings are append-only. Every column is mapped updatable = false, so once a row is
 * written the only legitimate way to reverse its effect is another posting, never an edit
 * or a delete. That is what makes a balance reconstructable: replaying an account's
 * postings in order must always arrive at the same number.
 *
 * A transfer between two accounts writes two postings — a debit on one side and a credit
 * on the other — sharing a transactionRef, in a single database transaction. Neither side
 * is meaningful alone, and the shared reference is what lets the pair be reconciled later.
 */
@Entity
@Table(name = "postings", schema = "ledger")
@Data
public class Posting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "posting_id")
    private Long postingId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, updatable = false)
    private PostingDirection direction;

    // Always positive — direction carries the sign. precision/scale rather than a
    // floating-point type: money must not accumulate binary rounding error.
    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "description", updatable = false)
    private String description;

    // Ties together the postings that make up one operation, so the two halves of a
    // transfer can be identified as a pair rather than inferred from timestamps.
    @Column(name = "transaction_ref", nullable = false, updatable = false, length = 36)
    private String transactionRef;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private Instant postedAt;
}
