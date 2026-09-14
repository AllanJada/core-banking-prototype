package org.learning.mldsa.repositories;

import jakarta.persistence.LockModeType;
import org.learning.mldsa.models.FileTransfer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FileTransferRepository extends JpaRepository<FileTransfer, Long> {

    // Ordering stays in the method name rather than being passed in: newest-first is what an
    // inbox means, not a choice the caller makes.
    Page<FileTransfer> findByReceiver_UserIdOrderBySentAtDesc(Long receiverId, Pageable pageable);

    Page<FileTransfer> findBySender_UserIdOrderBySentAtDesc(Long senderId, Pageable pageable);

    // Scoped by receiver so a user can only ever download files actually addressed to them.
    Optional<FileTransfer> findByTransferIdAndReceiver_UserId(Long transferId, Long receiverId);

    /**
     * The same lookup, holding a write lock for the rest of the transaction — used when a
     * transfer is being decided.
     *
     * Approving a slip now moves money, so two approvals arriving at once must not both get
     * past the "already reviewed" check and disburse the same instruction twice. The lock is
     * the same device PaymentLinkService uses to stop one link being paid twice, for exactly
     * the same reason.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from FileTransfer t where t.transferId = :transferId and t.receiver.userId = :receiverId")
    Optional<FileTransfer> findForReview(@Param("transferId") Long transferId,
                                         @Param("receiverId") Long receiverId);

    /**
     * Every transfer in the system, newest first — the oversight view, not scoped to any
     * participant. Only reachable through the Bank role's console.
     */
    Page<FileTransfer> findAllByOrderBySentAtDescTransferIdDesc(Pageable pageable);
}
