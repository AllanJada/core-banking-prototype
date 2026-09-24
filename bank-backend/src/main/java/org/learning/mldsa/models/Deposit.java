package org.learning.mldsa.models;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Money paid into a customer's account at the counter, funded from their institution's till.
 *
 * Taken by the institution, never by the account holder: a customer who could deposit into
 * their own account could create money, and every balance in the system would rest on that.
 * The institution that took it is recorded here, so each deposit has someone answerable for
 * it rather than only a beneficiary.
 *
 * Two postings, not one — the till is debited and the customer credited under a single
 * transactionRef. Nothing here can fail on insufficient funds: a till is allowed to run
 * negative, and by exactly how much is the question the institution's cash position answers.
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

    /**
     * The institution that took the deposit and whose till funded it.
     *
     * Nullable only because deposits taken before tellers existed have no institution to name,
     * and inventing one would assert that a bank accepted money it never saw.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "institution_id", updatable = false)
    private User institution;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "description", updatable = false)
    private String description;

    /** Ties this deposit to its posting in the ledger. */
    @Column(name = "transaction_ref", nullable = false, updatable = false, length = 36)
    private String transactionRef;

    /**
     * ML-DSA-65 signature made with the <em>institution's</em> key, over
     * accountNumber|institutionCode|amount|timestamp.
     *
     * The institution signs because the institution is the one making the claim: that it
     * received this money and credited this account. A deposit signed by the customer — as
     * these once were — had the beneficiary attesting to a payment they had not made.
     */
    @Column(name = "signature", nullable = false, updatable = false, columnDefinition = "text")
    private String signature;

    @Column(name = "deposited_at", nullable = false, updatable = false)
    private Instant depositedAt;
}
