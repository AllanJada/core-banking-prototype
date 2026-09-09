package org.learning.mldsa.models;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Money paid into an account, with no counterparty inside this system.
 *
 * The simplest of the money operations: one credit posting and a signed record of it.
 * Unlike a payment there is no debit side, so nothing here can fail on insufficient funds
 * — a deposit either is well-formed and recorded, or is rejected outright.
 */
@Entity
@Table(name = "deposits", schema = "payments")
@Data
public class Deposit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "deposit_id")
    private Long depositId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "description", updatable = false)
    private String description;

    /** Ties this deposit to its posting in the ledger. */
    @Column(name = "transaction_ref", nullable = false, updatable = false, length = 36)
    private String transactionRef;

    /** Ed25519 signature over accountNumber|amount|timestamp, made with the account owner's key. */
    @Column(name = "signature", nullable = false, updatable = false)
    private String signature;

    @Column(name = "deposited_at", nullable = false, updatable = false)
    private Instant depositedAt;
}
