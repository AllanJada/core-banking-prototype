package org.learning.mldsa.repositories;

import org.learning.mldsa.models.FileTransfer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FileTransferRepository extends JpaRepository<FileTransfer, Long> {

    List<FileTransfer> findByReceiver_UserIdOrderBySentAtDesc(Long receiverId);

    List<FileTransfer> findBySender_UserIdOrderBySentAtDesc(Long senderId);

    // Scoped by receiver so a user can only ever download files actually addressed to them.
    Optional<FileTransfer> findByTransferIdAndReceiver_UserId(Long transferId, Long receiverId);
}
