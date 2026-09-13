package org.learning.mldsa.services;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.models.Account;
import org.learning.mldsa.models.Payment;
import org.learning.mldsa.models.PaymentLink;
import org.learning.mldsa.models.PaymentLinkStatus;
import org.learning.mldsa.repositories.PaymentLinkRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payment requests: creating a shareable link, and settling one.
 *
 * Paying a link is not a separate way to move money — it resolves to an ordinary payment
 * through PaymentService, so every rule that applies to a normal payment (sufficient
 * funds, the transaction and daily caps, signing, the paired postings) applies here
 * unchanged and without being restated.
 */
@RequiredArgsConstructor
@Service
public class PaymentLinkService {

    private final PaymentLinkRepository paymentLinkRepository;
    private final PaymentService paymentService;
    private final AccountService accountService;

    @Value("${app.payments.link-max-lifetime-hours}")
    private long maxLifetimeHours;

    /** Creates a request for payment into the caller's own account. */
    @Transactional
    public PaymentLink create(Long requesterUserId, BigDecimal amount,
                              String description, Long expiresInHours) {
        paymentService.requireWellFormedAmount(amount);

        long lifetime = expiresInHours == null ? maxLifetimeHours : expiresInHours;
        if (lifetime <= 0 || lifetime > maxLifetimeHours) {
            throw new RuntimeException("Link lifetime must be between 1 and " + maxLifetimeHours + " hours");
        }

        Account requester = accountService.requireAccountFor(requesterUserId);
        Instant createdAt = Instant.now();

        PaymentLink link = new PaymentLink();
        link.setLinkId(UUID.randomUUID().toString());
        link.setRequesterAccount(requester);
        link.setAmount(amount);
        link.setDescription(description);
        link.setStatus(PaymentLinkStatus.PENDING);
        link.setExpiresAt(createdAt.plus(Duration.ofHours(lifetime)));
        link.setCreatedAt(createdAt);

        return paymentLinkRepository.save(link);
    }

    /** A link as anyone holding it may view it, to decide whether to pay. */
    public PaymentLink require(String linkId) {
        return paymentLinkRepository.findByLinkId(linkId)
                .orElseThrow(() -> new RuntimeException("No payment request found for that link"));
    }

    public Page<PaymentLink> listForRequester(Long requesterUserId, Pageable pageable) {
        Account account = accountService.requireAccountFor(requesterUserId);
        return paymentLinkRepository.findByRequesterAccount_AccountIdOrderByCreatedAtDescIdDesc(
                account.getAccountId(), pageable);
    }

    /**
     * Pays a link.
     *
     * Any authenticated customer may pay one — holding the link is the authorisation, the
     * same way a payment request works in practice.
     *
     * Expiry is checked here, at payment time, rather than only when the link was made:
     * the whole point of an expiring link is that it stops working once the deadline
     * passes, and a link is shared precisely so it can be opened later.
     *
     * The payment and the link's status change commit together. If the payment is refused
     * — insufficient funds, say — this transaction rolls back and the link stays PENDING,
     * so the payer can top up and try the same link again. The refusal is still recorded,
     * because that record is written in its own transaction.
     */
    @Transactional
    public Payment pay(Long payerUserId, String linkId) {
        PaymentLink link = paymentLinkRepository.findByLinkIdForUpdate(linkId)
                .orElseThrow(() -> new RuntimeException("No payment request found for that link"));

        switch (link.effectiveStatus()) {
            case PAID -> throw new RuntimeException("This payment request has already been paid");
            case CANCELLED -> throw new RuntimeException("This payment request was cancelled");
            case EXPIRED -> throw new RuntimeException("This payment request has expired");
            case PENDING -> { /* payable — carry on */ }
        }

        Account requester = link.getRequesterAccount();
        Account payer = accountService.requireAccountFor(payerUserId);
        if (payer.getAccountId().equals(requester.getAccountId())) {
            throw new RuntimeException("You cannot pay your own payment request");
        }

        Payment payment = paymentService.pay(
                payerUserId,
                requester.getAccountNumber(),
                link.getAmount(),
                link.getDescription());

        link.setStatus(PaymentLinkStatus.PAID);
        link.setPayment(payment);
        link.setPaidAt(Instant.now());
        paymentLinkRepository.save(link);

        return payment;
    }

    /** Withdraws a link, so it can no longer be paid. Only the requester may do this. */
    @Transactional
    public PaymentLink cancel(Long requesterUserId, String linkId) {
        PaymentLink link = paymentLinkRepository.findByLinkIdForUpdate(linkId)
                .orElseThrow(() -> new RuntimeException("No payment request found for that link"));

        Account requester = accountService.requireAccountFor(requesterUserId);
        if (!link.getRequesterAccount().getAccountId().equals(requester.getAccountId())) {
            throw new RuntimeException("Only the account that created this request can cancel it");
        }
        if (link.getStatus() == PaymentLinkStatus.PAID) {
            throw new RuntimeException("A paid payment request cannot be cancelled");
        }

        link.setStatus(PaymentLinkStatus.CANCELLED);
        return paymentLinkRepository.save(link);
    }
}
