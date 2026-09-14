package org.learning.mldsa.models;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Entity
@Table(name = "file_transfers", schema = "filetransfer")
@Data
public class FileTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transfer_id")
    private Long transferId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_id", nullable = false)
    private User receiver;

    // The filename as the sender's browser reported it — display-only, never used as a storage path.
    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    // Server-generated name the file is actually stored under on disk (see FileStorageService).
    @Column(name = "stored_filename", nullable = false, unique = true)
    private String storedFilename;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TransferStatus status;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "downloaded_at")
    private Instant downloadedAt;

    /** When the recipient approved or rejected this transfer. Null while still SENT. */
    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    /**
     * Why the recipient rejected this transfer. Null unless status is REJECTED.
     *
     * Required at rejection time (see FileTransferService.reject) — a rejection with no
     * reason gives the sender nothing to act on and gives an auditor nothing to check.
     */
    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    /**
     * Unique End-to-End Transaction Reference: the identifier ISO 20022 uses to follow one
     * payment across every institution that touches it.
     *
     * Generated once, here at origination, and never regenerated — that is the whole
     * property it provides. A UETR that changes as a payment moves is worse than none at
     * all, because it silently breaks the trail while still looking like a reference.
     *
     * Must be a UUID version 4 specifically; UUID.randomUUID() produces exactly that.
     *
     * Nullable because transfers created before this field existed have no UETR and cannot
     * be given one retrospectively without inventing a reference that was never sent.
     */
    @Column(name = "uetr", length = 36, updatable = false)
    private String uetr;

    // SHA-384 hash of the file content, computed at send time and re-verified on download.
    @Column(name = "file_hash")
    private String fileHash;

    /**
     * Where the ISO 20022 payload is stored on disk, when this transfer has one.
     *
     * Null for direct uploads and for everything sent before payload generation existed —
     * a plain file is not a payment instruction and has nothing to express as pain.001.
     */
    @Column(name = "stored_xml_filename", unique = true)
    private String storedXmlFilename;

    /**
     * SHA-384 of the payload's canonical form, not of its raw bytes.
     *
     * Its presence is also what decides which signing envelope applies to this transfer:
     * set means the signature covers both the PDF and the XML, null means the original
     * PDF-only envelope. See CryptoService.buildCombinedEnvelope.
     */
    @Column(name = "xml_hash")
    private String xmlHash;

    // Ed25519 signatures are a fixed 64 bytes — 88 characters Base64-encoded, so the TEXT
    // override needed for ML-DSA-65's ~4.4KB signatures no longer applies.
    @Column(name = "signature")
    private String signature;

    // Result of the most recent verification. True at creation time (just signed by us);
    // re-checked and possibly flipped to false on every download attempt.
    @Column(name = "signature_valid")
    private Boolean signatureValid;

    /**
     * The payment that disbursed this transfer's instruction, set when it was approved.
     *
     * A slip describes a credit transfer; approving it is what executes that description, and
     * this is the link between the document and the money it moved. Null for a plain file
     * upload, which carries no instruction, and for anything still awaiting review.
     *
     * One payment per transfer, enforced by a unique constraint as well as by the row lock
     * taken at approval: a transfer is decided once, so it can never disburse twice.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", unique = true)
    private Payment payment;
}
