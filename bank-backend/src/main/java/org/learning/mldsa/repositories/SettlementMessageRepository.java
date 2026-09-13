package org.learning.mldsa.repositories;

import org.learning.mldsa.models.SettlementMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SettlementMessageRepository extends JpaRepository<SettlementMessage, Long> {

    /** Every message, newest first — the Central Bank's view as settlement operator. */
    Page<SettlementMessage> findAllByOrderByCreatedAtDescMessageIdDesc(Pageable pageable);

    /**
     * The messages one institution is a party to, on either side.
     *
     * Both sides on purpose: a bank is as entitled to the instruction it received as to the
     * one it sent, and those are the only two it may see.
     */
    @Query("""
            select m from SettlementMessage m
            where m.debtorAgent.userId = :institutionId
               or m.creditorAgent.userId = :institutionId
            order by m.createdAt desc, m.messageId desc
            """)
    Page<SettlementMessage> findForInstitution(@Param("institutionId") Long institutionId, Pageable pageable);

    /**
     * One message, found only if the given institution is a party to it.
     *
     * The party check is inside the lookup rather than applied afterwards, so a message
     * between two other banks is as absent as an id that was never issued.
     */
    @Query("""
            select m from SettlementMessage m
            where m.messageId = :messageId
              and (m.debtorAgent.userId = :institutionId or m.creditorAgent.userId = :institutionId)
            """)
    Optional<SettlementMessage> findForInstitution(@Param("messageId") Long messageId,
                                                   @Param("institutionId") Long institutionId);

    List<SettlementMessage> findByPayment_PaymentIdIn(Collection<Long> paymentIds);
}
