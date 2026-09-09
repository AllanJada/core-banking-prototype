package org.learning.mldsa.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encrypts stored files with AES-256-GCM.
 *
 * Each file gets its own randomly generated data encryption key (DEK), which is then
 * wrapped under a single master key and written into the file's own header. One key per
 * file rather than one key for everything means compromising a single DEK exposes exactly
 * one document; it also keeps every key well clear of the limits on how much data may
 * safely be encrypted under one AES-GCM key.
 *
 * GCM is authenticated encryption: it detects modification of the ciphertext rather than
 * decrypting it into plausible-looking rubbish. That is a separate guarantee from the
 * Ed25519 signature over the plaintext, and both are kept — the signature proves who
 * produced the document, GCM proves the stored bytes were not altered underneath it.
 *
 * <p><b>What this does and does not protect against.</b> The master key comes from
 * configuration, so it is held by the running application. That defends against a stolen
 * disk, a database dump, or a copied backup — the realistic passive-compromise cases, and
 * exactly the gap recorded in the project's security notes. It does <i>not</i> defend
 * against an attacker who compromises the running server, because at that point they have
 * the master key too. Closing that would require the key to live somewhere the application
 * cannot read at will (a KMS or an HSM), which is a deployment change rather than a code
 * one.
 */
@Service
public class FileEncryptionService {

    /** Identifies a file this service wrote, so plaintext predating encryption is recognisable. */
    private static final byte[] MAGIC = "MLDSA1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /** Format version, so a future change to the header has somewhere to announce itself. */
    private static final byte FORMAT_VERSION = 1;

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BITS = 256;
    /** 96 bits, the nonce size GCM is specified around. */
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKey masterKey;

    public FileEncryptionService(@Value("${app.storage.master-key}") String masterKeyBase64) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(masterKeyBase64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.storage.master-key must be valid Base64", e);
        }
        if (keyBytes.length != KEY_BITS / 8) {
            // Failing at startup rather than encrypting everything under a key of the wrong
            // strength and discovering it later.
            throw new IllegalStateException(
                    "app.storage.master-key must decode to exactly 32 bytes for AES-256, got " + keyBytes.length);
        }
        this.masterKey = new SecretKeySpec(keyBytes, ALGORITHM);
    }

    /**
     * Encrypts content, returning a self-contained blob.
     *
     * The wrapped DEK travels in the file's header rather than in a database column, so a
     * stored file carries everything needed to decrypt it except the master key. Splitting
     * the two across disk and database would create a pairing that could be broken by
     * restoring one without the other.
     */
    public byte[] encrypt(byte[] plaintext) {
        try {
            SecretKey dek = generateDek();

            byte[] dekNonce = randomNonce();
            byte[] wrappedDek = cipher(Cipher.ENCRYPT_MODE, masterKey, dekNonce).doFinal(dek.getEncoded());

            byte[] fileNonce = randomNonce();
            byte[] ciphertext = cipher(Cipher.ENCRYPT_MODE, dek, fileNonce).doFinal(plaintext);

            return ByteBuffer.allocate(MAGIC.length + 1 + NONCE_BYTES + 2 + wrappedDek.length
                            + NONCE_BYTES + ciphertext.length)
                    .put(MAGIC)
                    .put(FORMAT_VERSION)
                    .put(dekNonce)
                    .putShort((short) wrappedDek.length)
                    .put(wrappedDek)
                    .put(fileNonce)
                    .put(ciphertext)
                    .array();
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt file content", e);
        }
    }

    /**
     * Decrypts content produced by {@link #encrypt}.
     *
     * Content without the header is returned unchanged: files stored before encryption
     * existed are still plaintext on disk, and refusing to read them would break every
     * transfer that predates this. Detection is by magic bytes rather than by a flag in the
     * database, so it cannot disagree with what is actually on disk.
     */
    public byte[] decrypt(byte[] stored) {
        if (!isEncrypted(stored)) {
            return stored;
        }

        try {
            ByteBuffer buffer = ByteBuffer.wrap(stored);
            buffer.position(MAGIC.length);

            byte version = buffer.get();
            if (version != FORMAT_VERSION) {
                throw new RuntimeException("Unsupported stored file format version: " + version);
            }

            byte[] dekNonce = new byte[NONCE_BYTES];
            buffer.get(dekNonce);

            byte[] wrappedDek = new byte[buffer.getShort()];
            buffer.get(wrappedDek);

            byte[] fileNonce = new byte[NONCE_BYTES];
            buffer.get(fileNonce);

            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            byte[] dekBytes = cipher(Cipher.DECRYPT_MODE, masterKey, dekNonce).doFinal(wrappedDek);
            SecretKey dek = new SecretKeySpec(dekBytes, ALGORITHM);

            return cipher(Cipher.DECRYPT_MODE, dek, fileNonce).doFinal(ciphertext);
        } catch (Exception e) {
            // Also the path a tampered file takes: GCM's authentication tag fails rather
            // than yielding altered plaintext.
            throw new RuntimeException("Failed to decrypt stored file — it may have been altered", e);
        }
    }

    /** Whether stored content carries this service's header. */
    public boolean isEncrypted(byte[] stored) {
        return stored != null
                && stored.length > MAGIC.length
                && Arrays.equals(Arrays.copyOf(stored, MAGIC.length), MAGIC);
    }

    private SecretKey generateDek() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance(ALGORITHM);
        generator.init(KEY_BITS);
        return generator.generateKey();
    }

    private byte[] randomNonce() {
        byte[] nonce = new byte[NONCE_BYTES];
        // Random per encryption. Reusing a nonce under the same key is the one mistake GCM
        // does not tolerate, and a fresh DEK per file keeps even that risk contained.
        secureRandom.nextBytes(nonce);
        return nonce;
    }

    private Cipher cipher(int mode, SecretKey key, byte[] nonce) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
        return cipher;
    }
}
