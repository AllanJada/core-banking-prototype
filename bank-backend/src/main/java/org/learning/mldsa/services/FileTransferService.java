package org.learning.mldsa.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.learning.mldsa.dtos.ComplianceProveResponse;
import org.learning.mldsa.dtos.FileTransferResponse;
import org.learning.mldsa.models.FileTransfer;
import org.learning.mldsa.models.TransferStatus;
import org.learning.mldsa.models.User;
import org.learning.mldsa.repositories.FileTransferRepository;
import org.learning.mldsa.repositories.UserRepositories;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
@Service
public class FileTransferService {

    private final FileTransferRepository fileTransferRepository;
    private final UserRepositories userRepositories;
    private final FileStorageService fileStorageService;
    private final CryptoService cryptoService;
    private final ZkComplianceService zkComplianceService;

    public FileTransferResponse sendFile(Long senderId, Long receiverId, MultipartFile file) {
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded file", e);
        }
        // null earnings/deductions: raw uploads never carry payslip line items, so they
        // opt out of compliance-proof generation entirely (see attachComplianceProof).
        return signEncryptAndPersist(senderId, receiverId, fileBytes, file.getOriginalFilename(), null, null);
    }

    public FileTransferResponse sendGeneratedFile(Long senderId, Long receiverId, byte[] fileBytes, String filename,
                                                   List<BigDecimal> earnings, List<BigDecimal> deductions) {
        return signEncryptAndPersist(senderId, receiverId, fileBytes, filename, earnings, deductions);
    }

    /**
     * Two independent crypto layers, in this order:
     *  1. Hash + sign the PLAINTEXT (unchanged from before ML-KEM was added).
     *  2. Encapsulate a fresh secret against the RECEIVER's ML-KEM public key and use it
     *     to AES-256-GCM-encrypt the plaintext — stored instead of the plaintext.
     *
     * earnings/deductions are non-null only for payslip sends (see sendGeneratedFile); when
     * present, a compliance proof is attached best-effort (attachComplianceProof) before the
     * transfer is saved. Passing null for both (as sendFile does) skips proof generation
     * entirely rather than attempting one against an empty payslip.
     */
    private FileTransferResponse signEncryptAndPersist(Long senderId, Long receiverId, byte[] fileBytes,
                                                         String originalFilename,
                                                         List<BigDecimal> earnings, List<BigDecimal> deductions) {
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
        if (receiver.getKemPublicKey() == null || receiver.getKemPublicKey().isBlank()) {
            throw new RuntimeException("Receiver does not have an ML-KEM key pair provisioned");
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

        PublicKey receiverKemPublicKey = cryptoService.decodeKemPublicKey(receiver.getKemPublicKey());
        CryptoService.Encapsulation encapsulation = cryptoService.encapsulate(receiverKemPublicKey);
        byte[] encryptedBytes = cryptoService.encryptWithSharedSecret(fileBytes, encapsulation.sharedSecret());

        String storedFilename = fileStorageService.store(encryptedBytes, originalFilename);

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
        transfer.setKemCiphertext(Base64.getEncoder().encodeToString(encapsulation.kemCiphertext()));

        attachComplianceProof(transfer, earnings, deductions);

        FileTransfer saved = fileTransferRepository.save(transfer);
        return toResponse(saved);
    }

    /**
     * Best-effort: requests a ZK compliance proof for a payslip's net pay from the standalone
     * zk-compliance-service and attaches it to the transfer being built. Never throws:
     * generation is skipped silently (transfer.complianceProof stays null) if earnings or
     * deductions is null (see signEncryptAndPersist), if any amount can't be represented
     * exactly in cents, or if the Rust proof service is unreachable, times out, or errors.
     * A payslip send must never be blocked or failed by this.
     */
    private void attachComplianceProof(FileTransfer transfer, List<BigDecimal> earnings, List<BigDecimal> deductions) {
        if (earnings == null || deductions == null) {
            return;
        }
        try {
            List<Long> earningsCents = toCents(earnings);
            List<Long> deductionsCents = toCents(deductions);
            ZkComplianceService.ProveResult result = zkComplianceService.prove(earningsCents, deductionsCents);
            transfer.setComplianceProof(result.proofBase64);
            transfer.setComplianceNetPayCents(result.netPayCents);
            transfer.setComplianceNumEntries(result.numEntries);
            transfer.setComplianceProofSizeBytes(result.proofSizeBytes);
        } catch (RuntimeException e) {
            log.warn("Compliance proof generation failed; sending payslip without a proof: {}", e.getMessage());
        }
    }

    // Mirrors SlipRequest.sum()'s null-filtering so a blank line-item amount is silently
    // skipped here exactly as it already is when the slip's own displayed totals are computed.
    private List<Long> toCents(List<BigDecimal> amounts) {
        return amounts.stream()
                .filter(Objects::nonNull)
                .map(amount -> amount.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact())
                .toList();
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
     * Returns the compliance proof attached to a transfer, if one was generated for it.
     * Scoped to the sender or receiver only, and empty (never an error) both when the caller
     * isn't one of those two and when the transfer simply has no proof attached: mirrors
     * downloadFile's "File not found, or you are not the recipient" pattern of not revealing
     * to an unauthorized caller whether the transfer even exists.
     */
    public Optional<ComplianceProveResponse> getComplianceProof(Long transferId, Long callerId) {
        return fileTransferRepository.findById(transferId)
                .filter(transfer -> transfer.getSender().getUserId().equals(callerId)
                        || transfer.getReceiver().getUserId().equals(callerId))
                .filter(transfer -> transfer.getComplianceProof() != null)
                .map(transfer -> new ComplianceProveResponse(
                        transfer.getComplianceNetPayCents(),
                        transfer.getComplianceNumEntries(),
                        transfer.getComplianceProof(),
                        transfer.getComplianceProofSizeBytes()
                ));
    }

    /**
     * Loads the file for download and, if this is the first download, flips the transfer's
     * status to DOWNLOADED. Scoped so only the actual recipient can download it.
     *
     * Decrypts first (ML-KEM decapsulate + AES-256-GCM decrypt, using the RECEIVER's own
     * key) to recover the plaintext, then runs the exact same integrity/authenticity check
     * that existed before encryption was added: independently recompute the hash from the
     * decrypted bytes and re-verify the ML-DSA signature against the sender's stored public
     * key. This confirms both that the plaintext hasn't been altered since it was signed,
     * and that the signature really was produced by the claimed sender's key — not merely
     * that the database's own stored fields agree with each other.
     */
    public FileDownload downloadFile(Long transferId, Long userId) {
        FileTransfer transfer = fileTransferRepository.findByTransferIdAndReceiver_UserId(transferId, userId)
                .orElseThrow(() -> new RuntimeException("File not found, or you are not the recipient"));

        byte[] plaintext = decryptAndVerifyOrThrow(transfer);

        if (transfer.getStatus() != TransferStatus.DOWNLOADED) {
            transfer.setStatus(TransferStatus.DOWNLOADED);
            transfer.setDownloadedAt(Instant.now());
            fileTransferRepository.save(transfer);
        }

        Resource resource = new ByteArrayResource(plaintext);
        return new FileDownload(resource, transfer.getOriginalFilename());
    }

    private byte[] decryptAndVerifyOrThrow(FileTransfer transfer) {
        User sender = transfer.getSender();
        User receiver = transfer.getReceiver();

        if (sender.getPublicKey() == null || sender.getPublicKey().isBlank()
                || transfer.getSignature() == null || transfer.getFileHash() == null) {
            markInvalid(transfer);
            throw new RuntimeException("This transfer has no valid signature on record and cannot be verified");
        }
        if (receiver.getKemPrivateKey() == null || receiver.getKemPrivateKey().isBlank()
                || transfer.getKemCiphertext() == null) {
            markInvalid(transfer);
            throw new RuntimeException("This transfer has no encryption key material on record and cannot be decrypted");
        }

        byte[] storedBytes;
        try (var in = fileStorageService.loadAsResource(transfer.getStoredFilename()).getInputStream()) {
            storedBytes = in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read stored file for decryption", e);
        }

        byte[] plaintext;
        try {
            PrivateKey receiverKemPrivateKey = cryptoService.decodeKemPrivateKey(receiver.getKemPrivateKey());
            byte[] kemCiphertext = Base64.getDecoder().decode(transfer.getKemCiphertext());
            byte[] sharedSecret = cryptoService.decapsulate(receiverKemPrivateKey, kemCiphertext);
            plaintext = cryptoService.decryptWithSharedSecret(storedBytes, sharedSecret);
        } catch (RuntimeException e) {
            markInvalid(transfer);
            throw new RuntimeException("Decryption failed: wrong key, or the stored file was altered", e);
        }

        String currentHash = cryptoService.hashFile(plaintext);
        if (!currentHash.equals(transfer.getFileHash())) {
            markInvalid(transfer);
            throw new RuntimeException("File integrity check failed: decrypted content no longer matches its signed hash");
        }

        String envelope = cryptoService.buildEnvelope(
                sender.getUserId(), receiver.getUserId(), transfer.getFileHash(),
                transfer.getOriginalFilename(), transfer.getSentAt().toEpochMilli()
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

        return plaintext;
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
                transfer.getSignatureValid(),
                transfer.getComplianceProof() != null
        );
    }
}
