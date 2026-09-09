package org.learning.mldsa.services;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.FileTransferResponse;
import org.learning.mldsa.dtos.PageResponse;
import org.learning.mldsa.models.FileTransfer;
import org.learning.mldsa.models.TransferStatus;
import org.learning.mldsa.models.User;
import org.learning.mldsa.repositories.FileTransferRepository;
import org.learning.mldsa.repositories.UserRepositories;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
@Service
public class FileTransferService {

    private final FileTransferRepository fileTransferRepository;
    private final UserRepositories userRepositories;
    private final FileStorageService fileStorageService;
    private final CryptoService cryptoService;
    private final XmlCanonicalizationService xmlCanonicalizationService;

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
        return sendGeneratedFile(senderId, receiverId, fileBytes, filename, null);
    }

    /**
     * Sends a generated document together with its ISO 20022 payload.
     *
     * Both artefacts are stored and then covered by one signature, in this single call —
     * the same reason the PDF is generated and signed together rather than handed back for
     * re-upload. If the payload were attached in a later step, the window between the two
     * would be exactly the gap where a document and the payment instruction beside it could
     * be made to disagree.
     */
    public FileTransferResponse sendGeneratedFile(Long senderId, Long receiverId, byte[] fileBytes,
                                                   String filename, Iso20022Payload payload) {
        String storedFilename = fileStorageService.store(fileBytes, filename);
        return signAndPersist(senderId, receiverId, fileBytes, storedFilename, filename, payload);
    }

    private FileTransferResponse signAndPersist(Long senderId, Long receiverId, byte[] fileBytes,
                                                 String storedFilename, String originalFilename) {
        return signAndPersist(senderId, receiverId, fileBytes, storedFilename, originalFilename, null);
    }

    private FileTransferResponse signAndPersist(Long senderId, Long receiverId, byte[] fileBytes,
                                                 String storedFilename, String originalFilename,
                                                 Iso20022Payload payload) {
        if (senderId.equals(receiverId)) {
            throw new RuntimeException("Sender and receiver must be different users");
        }

        User sender = userRepositories.findById(senderId)
                .orElseThrow(() -> new RuntimeException("Sender not found"));
        User receiver = userRepositories.findById(receiverId)
                .orElseThrow(() -> new RuntimeException("Receiver not found"));

        // Accounts exchange files with their own kind — an institution sends to another
        // institution. This was previously only a filter applied to the recipient dropdown
        // in the browser, which meant the rule vanished entirely for anyone calling the API
        // directly. Enforcing it here makes it a property of the system rather than of the UI.
        if (sender.getRole() != receiver.getRole()) {
            throw new RuntimeException("Recipient must be the same kind of account as the sender");
        }

        if (sender.getPrivateKey() == null || sender.getPrivateKey().isBlank()) {
            throw new RuntimeException("Sender does not have a signing key pair provisioned");
        }

        String fileHash = cryptoService.hashFile(fileBytes);

        // Minted at origination and carried unchanged from here on. A transfer that brought
        // its own payload already has one — the message embeds it — so that value is reused
        // rather than a second, conflicting reference being generated.
        String uetr = payload != null ? payload.uetr() : UUID.randomUUID().toString();

        // sentAt is fixed here, before signing, and reused verbatim (never regenerated)
        // both in the persisted row and when rebuilding the envelope to verify later.
        Instant sentAt = Instant.now();

        String storedXmlFilename = null;
        String xmlHash = null;
        String envelope;

        if (payload != null) {
            storedXmlFilename = fileStorageService.store(payload.xml(), payload.filename());
            // Hashed over the canonical form, so a recipient re-serialising the document
            // still arrives at this value. See XmlCanonicalizationService.
            xmlHash = cryptoService.hashFile(xmlCanonicalizationService.canonicalize(payload.xml()));
            envelope = cryptoService.buildCombinedEnvelope(
                    senderId, receiverId, fileHash, originalFilename, sentAt.toEpochMilli(), uetr, xmlHash);
        } else {
            envelope = cryptoService.buildEnvelope(
                    senderId, receiverId, fileHash, originalFilename, sentAt.toEpochMilli());
        }

        PrivateKey senderPrivateKey = cryptoService.decodePrivateKey(sender.getPrivateKey());
        String signature = cryptoService.sign(envelope, senderPrivateKey);

        FileTransfer transfer = new FileTransfer();
        transfer.setSender(sender);
        transfer.setReceiver(receiver);
        transfer.setUetr(uetr);
        transfer.setOriginalFilename(originalFilename);
        transfer.setStoredFilename(storedFilename);
        transfer.setStoredXmlFilename(storedXmlFilename);
        transfer.setFileHash(fileHash);
        transfer.setXmlHash(xmlHash);
        transfer.setSignature(signature);
        transfer.setSignatureValid(true); // known-true right now; re-checked on every download
        transfer.setStatus(TransferStatus.SENT);
        transfer.setSentAt(sentAt);

        FileTransfer saved = fileTransferRepository.save(transfer);
        return toResponse(saved);
    }

    public PageResponse<FileTransferResponse> getInbox(Long userId, Pageable pageable) {
        return PageResponse.of(
                fileTransferRepository.findByReceiver_UserIdOrderBySentAtDesc(userId, pageable),
                this::toResponse);
    }

    public PageResponse<FileTransferResponse> getOutbox(Long userId, Pageable pageable) {
        return PageResponse.of(
                fileTransferRepository.findBySender_UserIdOrderBySentAtDesc(userId, pageable),
                this::toResponse);
    }

    /**
     * Loads the file for download and, if this is the first download, flips the transfer's
     * status to DOWNLOADED. Scoped so only the actual recipient can download it.
     *
     * Before serving the file, independently recomputes its hash from the bytes actually
     * on disk right now and re-verifies the Ed25519 signature against the sender's stored
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

        // Which envelope was signed is decided by whether this transfer carries a payload,
        // exactly as it was at send time — so the string rebuilt here is the string signed.
        String envelope;
        if (transfer.getXmlHash() != null) {
            // Re-canonicalise and re-hash the stored payload rather than trusting the
            // recorded hash: comparing a stored value against itself would pass even if the
            // payload on disk had been replaced, which is the case worth detecting.
            byte[] currentXml = readStoredFile(transfer.getStoredXmlFilename());
            String currentXmlHash = cryptoService.hashFile(
                    xmlCanonicalizationService.canonicalize(currentXml));

            if (!currentXmlHash.equals(transfer.getXmlHash())) {
                markInvalid(transfer);
                throw new RuntimeException(
                        "Payload integrity check failed: the ISO 20022 payload no longer matches its signed hash");
            }

            envelope = cryptoService.buildCombinedEnvelope(
                    transfer.getSender().getUserId(),
                    transfer.getReceiver().getUserId(),
                    transfer.getFileHash(),
                    transfer.getOriginalFilename(),
                    transfer.getSentAt().toEpochMilli(),
                    transfer.getUetr(),
                    transfer.getXmlHash()
            );
        } else {
            envelope = cryptoService.buildEnvelope(
                    transfer.getSender().getUserId(),
                    transfer.getReceiver().getUserId(),
                    transfer.getFileHash(),
                    transfer.getOriginalFilename(),
                    transfer.getSentAt().toEpochMilli()
            );
        }

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

    /** Reads a stored file's current bytes from disk, for re-verification. */
    private byte[] readStoredFile(String storedFilename) {
        Resource resource = fileStorageService.loadAsResource(storedFilename);
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read stored file for verification", e);
        }
    }

    /**
     * Serves the ISO 20022 payload for a transfer that has one.
     *
     * Scoped to the recipient like the document download, and verified the same way: the
     * payload states what money should move, so serving one that no longer matches what was
     * signed would be worse than serving an altered PDF.
     */
    public FileDownload downloadPayload(Long transferId, Long userId) {
        FileTransfer transfer = fileTransferRepository.findByTransferIdAndReceiver_UserId(transferId, userId)
                .orElseThrow(() -> new RuntimeException("File not found, or you are not the recipient"));

        if (transfer.getStoredXmlFilename() == null) {
            throw new RuntimeException("This transfer has no ISO 20022 payload");
        }

        Resource resource = fileStorageService.loadAsResource(transfer.getStoredFilename());
        verifyIntegrityOrThrow(transfer, resource);

        String filename = stripExtension(transfer.getOriginalFilename()) + ".xml";
        return new FileDownload(
                fileStorageService.loadAsResource(transfer.getStoredXmlFilename()), filename);
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
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
                transfer.getUetr(),
                transfer.getStoredXmlFilename() != null
        );
    }
}
