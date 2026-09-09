package org.learning.mldsa.repositories;

import jakarta.persistence.LockModeType;
import org.learning.mldsa.models.PaymentLink;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentLinkRepository extends JpaRepository<PaymentLink, Long> {

    Optional<PaymentLink> findByLinkId(String linkId);

    Page<PaymentLink> findByRequesterAccount_AccountIdOrderByCreatedAtDescIdDesc(Long accountId, Pageable pageable);

    /**
     * Loads a link for payment, holding a row lock until the transaction ends.
     *
     * A link is payable exactly once, and checking its status and then updating it is a
     * read-then-write race: two people paying the same link at the same moment could both
     * read PENDING and both be charged. The lock makes the second payer wait for the first
     * to commit, so they read PAID and are turned away.
     *
     * Only for the payment path — plain reads should use findByLinkId and not take a lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from PaymentLink l where l.linkId = :linkId")
    Optional<PaymentLink> findByLinkIdForUpdate(@Param("linkId") String linkId);
}
