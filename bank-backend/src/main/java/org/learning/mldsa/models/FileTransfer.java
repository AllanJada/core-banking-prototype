package org.learning.mldsa.models;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Entity
@Table(name = "file_transfers")
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

    // SHA-384 hash of the file content, computed at send time and re-verified on download.
    @Column(name = "file_hash")
    private String fileHash;

    // ML-DSA-65 signatures are ~3.3KB raw / ~4.4KB Base64 — needs TEXT, not varchar(255).
    @Column(name = "signature", columnDefinition = "TEXT")
    private String signature;

    // Result of the most recent verification. True at creation time (just signed by us);
    // re-checked and possibly flipped to false on every download attempt.
    @Column(name = "signature_valid")
    private Boolean signatureValid;
}
