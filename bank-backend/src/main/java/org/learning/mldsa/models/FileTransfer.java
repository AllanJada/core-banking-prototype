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

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    // Server-generated name the file is actually stored under on disk (see FileStorageService).
    // What's on disk under this name is now the AES-256-GCM-encrypted blob, not plaintext —
    // see kemCiphertext below for how to get back to plaintext.
    @Column(name = "stored_filename", nullable = false, unique = true)
    private String storedFilename;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TransferStatus status;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "downloaded_at")
    private Instant downloadedAt;

    // SHA-384 hash of the PLAINTEXT file content, computed at send time (before
    // encryption) and re-verified on download (after decryption). Never the hash of the
    // encrypted bytes on disk — signing and encryption are independent layers.
    @Column(name = "file_hash")
    private String fileHash;

    @Column(name = "signature", columnDefinition = "TEXT")
    private String signature;

    @Column(name = "signature_valid")
    private Boolean signatureValid;

    // ML-KEM-768 ciphertext from encapsulating against the receiver's public key at send
    // time (~1.6KB Base64) — the receiver decapsulates this with their own private key to
    // recover the AES-256-GCM key that decrypts storedFilename's contents.
    @Column(name = "kem_ciphertext", columnDefinition = "TEXT")
    private String kemCiphertext;
}
