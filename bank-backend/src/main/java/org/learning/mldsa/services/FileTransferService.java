package org.learning.mldsa.services;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.FileTransferResponse;
import org.learning.mldsa.models.FileTransfer;
import org.learning.mldsa.models.TransferStatus;
import org.learning.mldsa.models.User;
import org.learning.mldsa.repositories.FileTransferRepository;
import org.learning.mldsa.repositories.UserRepositories;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.List;

@RequiredArgsConstructor
@Service
public class FileTransferService {

    private final FileTransferRepository fileTransferRepository;
    private final UserRepositories userRepositories;
    private final FileStorageService fileStorageService;
    private final CryptoService cryptoService;

    public FileTransferResponse sendFile(Long senderId, Long receiverId, MultipartFile file) {
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded file", e);
        }
        String storedFilename = fileStorageService.store(file);
        return signAndPersist(senderId, receiverId, fileBytes, storedFilename, file.getOriginalFilename());
    }

    /**
     * Sends a server-generated file (e.g. a rendered PDF slip) — the bytes here were
     * produced entirely server-side (see PdfGenerationService) and never existed as a
     * client-supplied upload, so there is no client-controlled byte content anywhere
     * between generation and signing. This is what "coupling generation to sending"
     * actually buys you: the hash and signature are computed over bytes the client never
     * had a chance to touch, not just bytes the client happened to upload unmodified.
     */
    public FileTransferResponse sendGeneratedFile(Long senderId, Long receiverId,
                                                   byte[] fileBytes, String filename) {
        String storedFilename = fileStorageService.store(fileBytes, filename);
        return signAndPersist(senderId, receiverId, fileBytes, storedFilename, filename);
    }

    private FileTransferResponse signAndPersist(Long senderId, Long receiverId, byte[] fileBytes,
                                                 String storedFilename, String originalFilename) {
        if (senderId.equals(receiverId)) {
            throw new RuntimeException("Sender and receiver must be different users");
        }

        User sender = userRepositories.findById(senderId)
                .orElseThrow(() -> new RuntimeException("Sender not found"));
        User receiver = userRepositories.findById(receiverId)
                .orElseThrow(() -> new RuntimeException("Receiver not found"));

        if (sender.getPrivateKey() == null || sender.getPrivateKey().isBlank()) {
            throw new RuntimeException("Sender does not have an ML-DSA key pair provisioned");
        }

        String fileHash = cryptoService.hashFile(fileBytes);

        // sentAt is fixed here, before signing, and reused verbatim (never regenerated)
        // both in the persisted row and when rebuilding the envelope to verify later.
        Instant sentAt = Instant.now();
        String envelope = cryptoService.buildEnvelope(
                senderId, receiverId, fileHash, originalFilename, sentAt.toEpochMilli()
        );

        PrivateKey senderPrivateKey = cryptoService.decodePrivateKey(sender.getPrivateKey());
        String signature = cryptoService.sign(envelope, senderPrivateKey);

        FileTransfer transfer = new FileTransfer();
        transfer.setSender(sender);
        transfer.setReceiver(receiver);
        transfer.setOriginalFilename(originalFilename);
        transfer.setStoredFilename(storedFilename);
        transfer.setFileHash(fileHash);
        transfer.setSignature(signature);
        transfer.setSignatureValid(true); // known-true right now; re-checked on every download
        transfer.setStatus(TransferStatus.SENT);
        transfer.setSentAt(sentAt);

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
     *
     * Before serving the file, independently recomputes its hash from the bytes actually
     * on disk right now and re-verifies the ML-DSA signature against the sender's stored
     * public key. This confirms both that the file hasn't been altered since it was
     * signed, and that the signature really was produced by the claimed sender's key —
     * not merely that the database's own stored fields agree with each other.
     */
    public FileDownload downloadFile(Long transferId, Long userId) {
        FileTransfer transfer = fileTransferRepository.findByTransferIdAndReceiver_UserId(transferId, userId)
                .orElseThrow(() -> new RuntimeException("File not found, or you are not the recipient"));

        Resource resource = fileStorageService.loadAsResource(transfer.getStoredFilename());

        verifyIntegrityOrThrow(transfer, resource);

        if (transfer.getStatus() != TransferStatus.DOWNLOADED) {
            transfer.setStatus(TransferStatus.DOWNLOADED);
            transfer.setDownloadedAt(Instant.now());
            fileTransferRepository.save(transfer);
        }

        return new FileDownload(resource, transfer.getOriginalFilename());
    }

    private void verifyIntegrityOrThrow(FileTransfer transfer, Resource resource) {
        User sender = transfer.getSender();

        if (sender.getPublicKey() == null || sender.getPublicKey().isBlank()
                || transfer.getSignature() == null || transfer.getFileHash() == null) {
            markInvalid(transfer);
            throw new RuntimeException("This transfer has no valid signature on record and cannot be verified");
        }

        byte[] currentBytes;
        try (InputStream in = resource.getInputStream()) {
            currentBytes = in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read stored file for verification", e);
        }

        String currentHash = cryptoService.hashFile(currentBytes);
        if (!currentHash.equals(transfer.getFileHash())) {
            markInvalid(transfer);
            throw new RuntimeException("File integrity check failed: stored file no longer matches its signed hash");
        }

        String envelope = cryptoService.buildEnvelope(
                transfer.getSender().getUserId(),
                transfer.getReceiver().getUserId(),
                transfer.getFileHash(),
                transfer.getOriginalFilename(),
                transfer.getSentAt().toEpochMilli()
        );

        PublicKey senderPublicKey = cryptoService.decodePublicKey(sender.getPublicKey());
        boolean valid = cryptoService.verify(envelope, transfer.getSignature(), senderPublicKey);

        if (!valid) {
            markInvalid(transfer);
            throw new RuntimeException("Signature verification failed for this transfer");
        }

        if (!Boolean.TRUE.equals(transfer.getSignatureValid())) {
            transfer.setSignatureValid(true);
            fileTransferRepository.save(transfer);
        }
    }

    private void markInvalid(FileTransfer transfer) {
        transfer.setSignatureValid(false);
        fileTransferRepository.save(transfer);
    }

    private FileTransferResponse toResponse(FileTransfer transfer) {
        return new FileTransferResponse(
                transfer.getTransferId(),
                transfer.getSender().getName(),
                transfer.getReceiver().getName(),
                transfer.getOriginalFilename(),
                transfer.getStatus().name(),
                transfer.getSentAt(),
                transfer.getDownloadedAt(),
                transfer.getFileHash(),
                transfer.getSignature(),
                transfer.getSignatureValid()
        );
    }
}
