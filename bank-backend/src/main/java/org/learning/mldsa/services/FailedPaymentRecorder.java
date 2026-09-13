package org.learning.mldsa.services;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.models.Payment;
import org.learning.mldsa.models.PaymentStatus;
import org.learning.mldsa.repositories.AccountRepository;
import org.learning.mldsa.repositories.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Records a rejected payment so the attempt survives the rejection.
 *
 * This exists as its own bean for one specific reason. A payment is rejected from inside
 * the transaction that was going to make it, and that transaction gets rolled back — which
 * would take the failure record down with it, leaving no trace of what was refused.
 * REQUIRES_NEW runs the write in a separate transaction that commits independently, so the
 * record outlives the rollback.
 *
 * It has to be a separate bean rather than another method on PaymentService because Spring
 * applies transaction settings through a proxy: a service calling its own method bypasses
 * that proxy entirely, and the propagation setting would be silently ignored.
 *
 * Accounts are passed by id and reloaded here rather than handed over as entities, since
 * the caller's instances belong to a persistence context that is about to be discarded.
 */
@RequiredArgsConstructor
@Service
public class FailedPaymentRecorder {

    private final PaymentRepository paymentRepository;
    private final AccountRepository accountRepository;

    /**
     * @param reason what the customer is told
     * @param detail the specific cause, for the institution and the Central Bank only; null
     *               when the reason itself is already the whole story
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long fromAccountId, Long toAccountId, BigDecimal amount,
                       String description, String reason, String detail) {
        Payment payment = new Payment();
        payment.setFromAccount(accountRepository.getReferenceById(fromAccountId));
        // Null when the rejection was that no such recipient exists.
        if (toAccountId != null) {
            payment.setToAccount(accountRepository.getReferenceById(toAccountId));
        }
        payment.setAmount(amount);
        payment.setDescription(description);
        payment.setStatus(PaymentStatus.FAILED);
        payment.setFailureReason(reason);
        payment.setFailureDetail(detail);
        payment.setCreatedAt(Instant.now());
        // transactionRef and signature stay null: no postings were written, and nothing
        // that did not happen gets signed.

        paymentRepository.save(payment);
    }
}
