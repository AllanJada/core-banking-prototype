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

    /**
     * Completed payments that crossed institutions — the movements that moved settlement
     * positions, newest first.
     *
     * Which payments these are is derived by comparing the two accounts' institutions rather
     * than stored on the payment: the ledger already knows where each account is held, and a
     * duplicated flag could disagree with it.
     */
    @Query("""
            select p from Payment p
            where p.status = org.learning.mldsa.models.PaymentStatus.COMPLETED
              and p.toAccount is not null
              and p.fromAccount.institution.userId <> p.toAccount.institution.userId
            order by p.createdAt desc, p.paymentId desc
            """)
    Page<Payment> findInterBankCompleted(Pageable pageable);

    /** Refusals carrying a settlement detail: what a bank could not settle, and why. */
    @Query("""
            select p from Payment p
            where p.status = org.learning.mldsa.models.PaymentStatus.FAILED
              and p.failureDetail is not null
            order by p.createdAt desc, p.paymentId desc
            """)
    Page<Payment> findSettlementRefusals(Pageable pageable);

    /** Every payment made by one institution's own customers, refused ones included. */
    @Query("""
            select p from Payment p
            where p.fromAccount.institution.userId = :institutionId
            order by p.createdAt desc, p.paymentId desc
            """)
    Page<Payment> findByPayingInstitution(@Param("institutionId") Long institutionId, Pageable pageable);
}
