package org.learning.mldsa.repositories;

import org.learning.mldsa.models.FileTransfer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FileTransferRepository extends JpaRepository<FileTransfer, Long> {

    // Ordering stays in the method name rather than being passed in: newest-first is what an
    // inbox means, not a choice the caller makes.
    Page<FileTransfer> findByReceiver_UserIdOrderBySentAtDesc(Long receiverId, Pageable pageable);

    Page<FileTransfer> findBySender_UserIdOrderBySentAtDesc(Long senderId, Pageable pageable);

    // Scoped by receiver so a user can only ever download files actually addressed to them.
    Optional<FileTransfer> findByTransferIdAndReceiver_UserId(Long transferId, Long receiverId);

    /**
     * Every transfer in the system, newest first — the oversight view, not scoped to any
     * participant. Only reachable through the Bank role's console.
     */
    Page<FileTransfer> findAllByOrderBySentAtDescTransferIdDesc(Pageable pageable);
}
