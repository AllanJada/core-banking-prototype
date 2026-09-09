package org.learning.mldsa.repositories;

import org.learning.mldsa.models.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Page<Payment> findByFromAccount_AccountIdOrderByCreatedAtDescPaymentIdDesc(Long accountId, Pageable pageable);

    /** Every payment in the system, successful and refused alike, newest first. */
    Page<Payment> findAllByOrderByCreatedAtDescPaymentIdDesc(Pageable pageable);

    /**
     * Total value this account has successfully paid out since the given instant, for the
     * daily cap check.
     *
     * Counts COMPLETED payments only: a rejected attempt moved no money, so letting it
     * consume someone's daily allowance would punish them for a payment that never
     * happened.
     */
    @Query("""
            select coalesce(sum(p.amount), 0)
            from Payment p
            where p.fromAccount.accountId = :accountId
              and p.status = org.learning.mldsa.models.PaymentStatus.COMPLETED
              and p.createdAt >= :since
            """)
    BigDecimal sumCompletedSince(@Param("accountId") Long accountId, @Param("since") Instant since);
}
