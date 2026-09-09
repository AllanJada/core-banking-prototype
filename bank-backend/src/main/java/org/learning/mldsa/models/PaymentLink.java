package org.learning.mldsa.models;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A shareable request for payment: "pay me this amount, here is the link".
 *
 * The link itself moves no money. Paying one creates an ordinary Payment from the payer's
 * account to the requester's, so a paid link is reconcilable through exactly the same
 * ledger postings as any other payment rather than being a parallel way to move money.
 */
@Entity
@Table(name = "payment_links", schema = "payments")
@Data
public class PaymentLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    // The public identifier that gets shared. A random UUID rather than the sequential id
    // above, so possessing one link tells you nothing about anyone else's.
    @Column(name = "link_id", nullable = false, unique = true, updatable = false, length = 36)
    private String linkId;

    /** The account that gets paid. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_account_id", nullable = false, updatable = false)
    private Account requesterAccount;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "description", updatable = false)
    private String description;

    // Only ever PENDING, PAID or CANCELLED in the database — see PaymentLinkStatus for why
    // EXPIRED is derived rather than stored.
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PaymentLinkStatus status;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** The payment that settled this link; null until it is paid. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * The status as it actually stands right now, accounting for the clock.
     *
     * A link that is still PENDING in the database but past its expiry is EXPIRED. Callers
     * should use this rather than the stored status for any decision about whether the
     * link can be paid.
     */
    public PaymentLinkStatus effectiveStatus() {
        if (status == PaymentLinkStatus.PENDING && Instant.now().isAfter(expiresAt)) {
            return PaymentLinkStatus.EXPIRED;
        }
        return status;
    }
}
