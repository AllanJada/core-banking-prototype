package org.learning.mldsa.services;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.FileTransferResponse;
import org.learning.mldsa.dtos.PageResponse;
import org.learning.mldsa.dtos.PaymentPreviewResponse;
import org.learning.mldsa.models.FileTransfer;
import org.learning.mldsa.models.TransferStatus;
import org.learning.mldsa.models.User;
import org.learning.mldsa.repositories.FileTransferRepository;
import org.learning.mldsa.repositories.UserRepositories;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.UUID;

@RequiredArgsConstructor
@Service
public class FileTransferService {

    private final FileTransferRepository fileTransferRepository;
    private final UserRepositories userRepositories;
    private final FileStorageService fileStorageService;
    private final CryptoService cryptoService;
    private final XmlCanonicalizationService xmlCanonicalizationService;
    private final Pain001GenerationService pain001GenerationService;
    private final SlipDisbursementService slipDisbursementService;

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
        transfer.setSignatureValid(true); // known-true right now; re-checked on every review or download
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
     * What a recipient sees before deciding to approve or reject a transfer.
     *
     * Unlike download, this never throws on a failed integrity check — it reports the
     * failure in the response instead. A review step exists precisely to catch a transfer
     * that looks wrong before committing to it, so the one place that must not simply error
     * out is the one place a reviewer is looking for exactly that signal. Re-runs the check
     * on every call, so it reflects the file as it stands on disk right now, not a cached
     * verdict from send time.
     *
     * Payload fields are parsed and shown only when the integrity check passes — content
     * that fails verification is not trustworthy enough to summarise as fact.
     */
    public PaymentPreviewResponse preview(Long transferId, Long userId) {
        FileTransfer transfer = fileTransferRepository.findByTransferIdAndReceiver_UserId(transferId, userId)
                .orElseThrow(() -> new RuntimeException("File not found, or you are not the recipient"));

        IntegrityCheck check = safeCheckIntegrity(transfer);
        recordIntegrityResult(transfer, check);

        PaymentPreviewResponse.PayloadPreview payloadPreview = null;
        if (check.valid() && transfer.getStoredXmlFilename() != null) {
            payloadPreview = pain001GenerationService.parsePreview(readStoredFile(transfer.getStoredXmlFilename()));
        }

        return new PaymentPreviewResponse(
                transfer.getTransferId(),
                transfer.getSender().getName(),
                transfer.getReceiver().getName(),
                transfer.getOriginalFilename(),
                transfer.getStatus().name(),
                transfer.getSentAt(),
                transfer.getUetr(),
                transfer.getStoredXmlFilename() != null,
                check.valid(),
                check.failureReason(),
                payloadPreview
        );
    }

    /**
     * The document's actual bytes, for rendering inline during review.
     *
     * Available regardless of status — a rejected transfer should still be viewable so the
     * recipient can double-check why they rejected it — but unlike the structured preview
     * above, this does throw on a failed integrity check: there is no safe way to render a
     * tampered PDF "with a warning attached", so the reviewer is directed back to the
     * structured preview instead, where the failure is explained rather than just refused.
     */
    public FileDownload previewDocument(Long transferId, Long userId) {
        FileTransfer transfer = fileTransferRepository.findByTransferIdAndReceiver_UserId(transferId, userId)
                .orElseThrow(() -> new RuntimeException("File not found, or you are not the recipient"));

        Resource resource = fileStorageService.loadAsResource(transfer.getStoredFilename());
        verifyIntegrityOrThrow(transfer, resource);

        return new FileDownload(resource, transfer.getOriginalFilename());
    }

    /**
     * Accepts a transfer, allowing it to be picked up (downloaded).
     *
     * Re-verifies integrity at the moment of the decision rather than trusting an earlier
     * preview — the file on disk could have changed between the two, however unlikely, and
     * an approval is a claim that this specific document, right now, is what it was signed
     * as. Only reachable from SENT: a transfer that has already been decided cannot be
     * decided again.
     */
    @Transactional
    public FileTransferResponse approve(Long transferId, Long userId) {
        FileTransfer transfer = requireReviewable(transferId, userId);

        Resource resource = fileStorageService.loadAsResource(transfer.getStoredFilename());
        verifyIntegrityOrThrow(transfer, resource);

        // A slip carries a payment instruction, and approving it is what executes it — this is
        // the moment a document becomes money. Deliberately after the integrity check above:
        // the amount and accounts are read out of the signed payload, so proving the payload
        // is unaltered has to come first. A disbursement that cannot be made throws, which
        // rolls back this approval and leaves the transfer awaiting review rather than
        // approved-but-unpaid.
        if (transfer.getStoredXmlFilename() != null) {
            transfer.setPayment(slipDisbursementService.disburse(
                    transfer, readStoredFile(transfer.getStoredXmlFilename())));
        }

        transfer.setStatus(TransferStatus.APPROVED);
        transfer.setReviewedAt(Instant.now());
        return toResponse(fileTransferRepository.save(transfer));
    }

    /**
     * Refuses a transfer. Terminal: a rejected transfer can never be downloaded, and cannot
     * later be approved.
     *
     * A reason is mandatory. "Rejected" with nothing else gives the sender nothing to
     * correct and gives anyone auditing the exchange nothing to check the decision against —
     * the whole value of a review step is the trail it leaves, not just the refusal itself.
     */
    @Transactional
    public FileTransferResponse reject(Long transferId, Long userId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new RuntimeException("A reason is required to reject a transfer");
        }

        FileTransfer transfer = requireReviewable(transferId, userId);
        transfer.setStatus(TransferStatus.REJECTED);
        transfer.setReviewedAt(Instant.now());
        transfer.setRejectionReason(reason.trim());
        return toResponse(fileTransferRepository.save(transfer));
    }

    private FileTransfer requireReviewable(Long transferId, Long userId) {
        // Locked for the rest of the transaction: approving now moves money, so two decisions
        // arriving at once must not both pass the status check below.
        FileTransfer transfer = fileTransferRepository.findForReview(transferId, userId)
                .orElseThrow(() -> new RuntimeException("File not found, or you are not the recipient"));
        if (transfer.getStatus() != TransferStatus.SENT) {
            throw new RuntimeException("This transfer has already been reviewed");
        }
        return transfer;
    }

    /**
     * Loads the file for download and, if this is the first download, flips the transfer's
     * status to DOWNLOADED. Scoped so only the actual recipient can download it.
     *
     * Requires the transfer to have been approved first — see requirePickupAllowed. Before
     * serving the file, independently recomputes its hash from the bytes actually on disk
     * right now and re-verifies the Ed25519 signature against the sender's stored public
     * key. This confirms both that the file hasn't been altered since it was signed, and
     * that the signature really was produced by the claimed sender's key — not merely that
     * the database's own stored fields agree with each other.
     */
    public FileDownload downloadFile(Long transferId, Long userId) {
        FileTransfer transfer = fileTransferRepository.findByTransferIdAndReceiver_UserId(transferId, userId)
                .orElseThrow(() -> new RuntimeException("File not found, or you are not the recipient"));

        requirePickupAllowed(transfer);

        Resource resource = fileStorageService.loadAsResource(transfer.getStoredFilename());

        verifyIntegrityOrThrow(transfer, resource);

        if (transfer.getStatus() != TransferStatus.DOWNLOADED) {
            transfer.setStatus(TransferStatus.DOWNLOADED);
            transfer.setDownloadedAt(Instant.now());
            fileTransferRepository.save(transfer);
        }

        return new FileDownload(resource, transfer.getOriginalFilename());
    }

    /**
     * The gate that makes the review module real rather than cosmetic: a transfer cannot be
     * picked up until it has been approved, and never again once it has been rejected.
     */
    private void requirePickupAllowed(FileTransfer transfer) {
        switch (transfer.getStatus()) {
            case SENT -> throw new RuntimeException(
                    "This transfer must be previewed and approved before it can be downloaded");
            case REJECTED -> throw new RuntimeException(
                    "This transfer was rejected and cannot be downloaded");
            case APPROVED, DOWNLOADED -> {
                // Pickup allowed — either the first time, or a repeat download.
            }
        }
    }

    /** Whether an integrity check passed, and why not when it didn't. */
    private record IntegrityCheck(boolean valid, String failureReason) {
        static IntegrityCheck ok() {
            return new IntegrityCheck(true, null);
        }

        static IntegrityCheck failure(String reason) {
            return new IntegrityCheck(false, reason);
        }
    }

    /**
     * Re-derives whether a transfer's stored bytes still match what was signed, without
     * throwing or touching the transfer's persisted state — a pure check, so it can be
     * reused by both the throwing legacy path (download) and the resilient one (preview).
     */
    private IntegrityCheck checkIntegrity(FileTransfer transfer, Resource resource) {
        User sender = transfer.getSender();

        if (sender.getPublicKey() == null || sender.getPublicKey().isBlank()
                || transfer.getSignature() == null || transfer.getFileHash() == null) {
            return IntegrityCheck.failure("This transfer has no valid signature on record and cannot be verified");
        }

        byte[] currentBytes;
        try (InputStream in = resource.getInputStream()) {
            currentBytes = in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read stored file for verification", e);
        }

        String currentHash = cryptoService.hashFile(currentBytes);
        if (!currentHash.equals(transfer.getFileHash())) {
            return IntegrityCheck.failure("File integrity check failed: stored file no longer matches its signed hash");
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
                return IntegrityCheck.failure(
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

        return valid ? IntegrityCheck.ok() : IntegrityCheck.failure("Signature verification failed for this transfer");
    }

    /**
     * Runs the integrity check with nothing allowed to throw past it, including failures
     * that happen before checkIntegrity itself gets a chance to run.
     *
     * The stored file is encrypted at rest (see FileEncryptionService), which means the
     * single most realistic form of tampering — flipping a bit in the file on disk — fails
     * AES-GCM authentication during decryption, before there is any plaintext to hash and
     * compare. That failure surfaces as a thrown exception out of loadAsResource, not as a
     * mismatch checkIntegrity would catch on its own. Without this wrapper, preview's
     * promise not to throw would hold for a much narrower class of problems than the one
     * this review step actually exists to catch.
     */
    private IntegrityCheck safeCheckIntegrity(FileTransfer transfer) {
        try {
            Resource resource = fileStorageService.loadAsResource(transfer.getStoredFilename());
            return checkIntegrity(transfer, resource);
        } catch (RuntimeException e) {
            return IntegrityCheck.failure(e.getMessage());
        }
    }

    /** Persists the outcome of an integrity check onto the transfer's signatureValid flag. */
    private void recordIntegrityResult(FileTransfer transfer, IntegrityCheck result) {
        if (result.valid()) {
            if (!Boolean.TRUE.equals(transfer.getSignatureValid())) {
                transfer.setSignatureValid(true);
                fileTransferRepository.save(transfer);
            }
        } else {
            markInvalid(transfer);
        }
    }

    private void verifyIntegrityOrThrow(FileTransfer transfer, Resource resource) {
        IntegrityCheck result = checkIntegrity(transfer, resource);
        recordIntegrityResult(transfer, result);
        if (!result.valid()) {
            throw new RuntimeException(result.failureReason());
        }
    }

    /** Reads a stored file's current bytes from disk, for re-verification or preview parsing. */
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
     * Scoped to the recipient like the document download, gated by the same approval
     * requirement, and verified the same way: the payload states what money should move, so
     * serving one that no longer matches what was signed would be worse than serving an
     * altered PDF.
     */
    public FileDownload downloadPayload(Long transferId, Long userId) {
        FileTransfer transfer = fileTransferRepository.findByTransferIdAndReceiver_UserId(transferId, userId)
                .orElseThrow(() -> new RuntimeException("File not found, or you are not the recipient"));

        if (transfer.getStoredXmlFilename() == null) {
            throw new RuntimeException("This transfer has no ISO 20022 payload");
        }

        requirePickupAllowed(transfer);

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
                transfer.getStoredXmlFilename() != null,
                transfer.getReviewedAt(),
                transfer.getRejectionReason(),
                transfer.getPayment() == null ? null : transfer.getPayment().getPaymentId(),
                transfer.getPayment() == null ? null : transfer.getPayment().getAmount()
        );
    }
}
