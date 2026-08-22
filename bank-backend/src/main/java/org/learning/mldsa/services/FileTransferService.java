package org.learning.mldsa.services;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.FileTransferResponse;
import org.learning.mldsa.models.FileTransfer;
import org.learning.mldsa.models.TransferStatus;
import org.learning.mldsa.models.User;
import org.learning.mldsa.repositories.FileTransferRepository;
import org.learning.mldsa.repositories.UserRepositories;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.List;

@RequiredArgsConstructor
@Service
public class FileTransferService {

    private final FileTransferRepository fileTransferRepository;
    private final UserRepositories userRepositories;
    private final FileStorageService fileStorageService;

    public FileTransferResponse sendFile(Long senderId, Long receiverId, MultipartFile file) {
        if (senderId.equals(receiverId)) {
            throw new RuntimeException("Sender and receiver must be different users");
        }

        User sender = userRepositories.findById(senderId)
                .orElseThrow(() -> new RuntimeException("Sender not found"));
        User receiver = userRepositories.findById(receiverId)
                .orElseThrow(() -> new RuntimeException("Receiver not found"));

        String storedFilename = fileStorageService.store(file);

        FileTransfer transfer = new FileTransfer();
        transfer.setSender(sender);
        transfer.setReceiver(receiver);
        transfer.setOriginalFilename(file.getOriginalFilename());
        transfer.setStoredFilename(storedFilename);
        transfer.setStatus(TransferStatus.SENT);
        transfer.setSentAt(Instant.now());

        FileTransfer saved = fileTransferRepository.save(transfer);
        return toResponse(saved);
    }

    public List<FileTransferResponse> getInbox(Long userId) {
        return fileTransferRepository.findByReceiver_UserIdOrderBySentAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public List<FileTransferResponse> getOutbox(Long userId) {
        return fileTransferRepository.findBySender_UserIdOrderBySentAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Loads the file for download and, if this is the first download, flips the transfer's
     * status to DOWNLOADED. Scoped so only the actual recipient can download it.
     */
    public FileDownload downloadFile(Long transferId, Long userId) {
        FileTransfer transfer = fileTransferRepository.findByTransferIdAndReceiver_UserId(transferId, userId)
                .orElseThrow(() -> new RuntimeException("File not found, or you are not the recipient"));

        var resource = fileStorageService.loadAsResource(transfer.getStoredFilename());

        if (transfer.getStatus() != TransferStatus.DOWNLOADED) {
            transfer.setStatus(TransferStatus.DOWNLOADED);
            transfer.setDownloadedAt(Instant.now());
            fileTransferRepository.save(transfer);
        }

        return new FileDownload(resource, transfer.getOriginalFilename());
    }

    private FileTransferResponse toResponse(FileTransfer transfer) {
        return new FileTransferResponse(
                transfer.getTransferId(),
                transfer.getSender().getName(),
                transfer.getReceiver().getName(),
                transfer.getOriginalFilename(),
                transfer.getStatus().name(),
                transfer.getSentAt(),
                transfer.getDownloadedAt()
        );
    }
}
