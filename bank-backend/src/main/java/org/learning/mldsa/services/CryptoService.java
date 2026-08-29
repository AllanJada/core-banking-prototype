package org.learning.mldsa.services;

import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KEM;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * ML-DSA-65 (FIPS 204) signing/verification, ML-KEM-768 (FIPS 203) key encapsulation,
 * AES-256-GCM bulk encryption using the KEM-derived secret, and SHA-384 file hashing.
 *
 * Uses the JDK's own built-in implementations (JEP 496 / JEP 497), not Bouncy Castle: both
 * shipped natively starting in JDK 24, and this project targets Java 26, so no external PQC
 * library is needed for these two algorithms. Every method below was verified end-to-end
 * against a real, compiled JDK 25 build (keygen, encode/decode round-trip through
 * Base64/X.509/PKCS8 exactly as User/FileTransfer store these fields, sign/verify,
 * encapsulate/decapsulate, and AES-256-GCM encrypt/decrypt with the derived secret) — this
 * is not a from-the-docs guess. JDK 25 was the newest available in the environment this was
 * verified in; the project targets 26, so re-run `mvn test` on the real Java 26 toolchain
 * before relying on this beyond the demo, since a version bump could in principle change
 * something, however unlikely given JEP 496/497 were already final (not preview) in 24.
 *
 * One thing worth knowing if you inspect stored keys: the encoded private keys are much
 * smaller than the raw FIPS 203/204 key sizes suggest (ML-DSA-65 ~72 chars Base64,
 * ML-KEM-768 ~116 chars Base64, not the ~5.4KB/~3.2KB you'd expect from the raw expanded
 * key sizes) — the JDK stores the compact seed form and re-expands it on load, which is
 * legal per the standards and confirmed working via the round-trip test above. Public keys
 * are close to the raw sizes plus normal X.509 wrapper overhead.
 */
@Service
public class CryptoService {

    private static final String DSA_ALGORITHM = "ML-DSA-65";
    private static final String KEM_ALGORITHM = "ML-KEM-768";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    // ---- ML-DSA-65 : signing ----

    public KeyPair generateMlDsaKeyPair() {
        try {
            return KeyPairGenerator.getInstance(DSA_ALGORITHM).generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to generate ML-DSA key pair", e);
        }
    }

    public String encodePublicKey(PublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    public String encodePrivateKey(PrivateKey privateKey) {
        return Base64.getEncoder().encodeToString(privateKey.getEncoded());
    }

    public PublicKey decodePublicKey(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            KeyFactory factory = KeyFactory.getInstance(DSA_ALGORITHM);
            return factory.generatePublic(new X509EncodedKeySpec(bytes));
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to decode ML-DSA public key", e);
        }
    }

    public PrivateKey decodePrivateKey(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            KeyFactory factory = KeyFactory.getInstance(DSA_ALGORITHM);
            return factory.generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to decode ML-DSA private key", e);
        }
    }

    /** SHA-384 hash of the given bytes, as a lowercase hex string. */
    public String hashFile(byte[] fileBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-384");
            byte[] hash = digest.digest(fileBytes);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-384 not available", e);
        }
    }

    /**
     * Canonical, order-sensitive string representation of a transfer's identity. This exact
     * string is what gets signed at send time and rebuilt (from the same persisted values,
     * never recomputed fresh) to verify at download time — changing the field order or
     * separator here invalidates every previously-issued signature.
     *
     * Always built from the PLAINTEXT file's hash, before encryption — signing covers "what
     * this content is", encryption (below) covers "who's allowed to read it in transit and
     * at rest". The two are deliberately independent layers.
     */
    public String buildEnvelope(Long senderId, Long receiverId, String fileHash,
                                 String originalFilename, long sentAtEpochMilli) {
        return senderId + "|" + receiverId + "|" + fileHash + "|" + originalFilename + "|" + sentAtEpochMilli;
    }

    public String sign(String payload, PrivateKey privateKey) {
        try {
            Signature signature = Signature.getInstance(DSA_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to sign transfer envelope", e);
        }
    }

    public boolean verify(String payload, String signatureBase64, PublicKey publicKey) {
        try {
            Signature signature = Signature.getInstance(DSA_ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(payload.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to verify transfer signature", e);
        }
    }

    // ---- ML-KEM-768 : key encapsulation, for confidentiality ----

    public KeyPair generateMlKemKeyPair() {
        try {
            return KeyPairGenerator.getInstance(KEM_ALGORITHM).generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to generate ML-KEM key pair", e);
        }
    }

    public String encodeKemPublicKey(PublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    public String encodeKemPrivateKey(PrivateKey privateKey) {
        return Base64.getEncoder().encodeToString(privateKey.getEncoded());
    }

    public PublicKey decodeKemPublicKey(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            KeyFactory factory = KeyFactory.getInstance(KEM_ALGORITHM);
            return factory.generatePublic(new X509EncodedKeySpec(bytes));
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to decode ML-KEM public key", e);
        }
    }

    public PrivateKey decodeKemPrivateKey(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            KeyFactory factory = KeyFactory.getInstance(KEM_ALGORITHM);
            return factory.generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to decode ML-KEM private key", e);
        }
    }

    /** Result of encapsulating a fresh shared secret against a receiver's ML-KEM public key. */
    public record Encapsulation(byte[] kemCiphertext, byte[] sharedSecret) {}

    /**
     * Sender side. Call this against the RECEIVER's ML-KEM public key. Produces a fresh
     * shared secret (never reused across transfers) plus the ciphertext that travels
     * alongside the transfer so the receiver can recover the same secret.
     */
    public Encapsulation encapsulate(PublicKey receiverKemPublicKey) {
        try {
            KEM kem = KEM.getInstance(KEM_ALGORITHM);
            KEM.Encapsulator encapsulator = kem.newEncapsulator(receiverKemPublicKey);
            KEM.Encapsulated encapsulated = encapsulator.encapsulate();
            return new Encapsulation(encapsulated.encapsulation(), encapsulated.key().getEncoded());
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to encapsulate ML-KEM shared secret", e);
        }
    }

    /**
     * Receiver side. Call this with the RECEIVER's own ML-KEM private key and the stored
     * kemCiphertext to recover the exact same shared secret the sender derived. A mismatched
     * key (wrong receiver) silently yields a different secret rather than throwing — by
     * design, per FIPS 203's implicit-rejection property — so the actual failure surfaces
     * one step later, as an AES-GCM authentication failure in decrypt().
     */
    public byte[] decapsulate(PrivateKey receiverKemPrivateKey, byte[] kemCiphertext) {
        try {
            KEM kem = KEM.getInstance(KEM_ALGORITHM);
            KEM.Decapsulator decapsulator = kem.newDecapsulator(receiverKemPrivateKey);
            return decapsulator.decapsulate(kemCiphertext).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to decapsulate ML-KEM shared secret", e);
        }
    }

    // ---- AES-256-GCM, keyed by the ML-KEM shared secret ----

    /**
     * Encrypts plaintext under the given 32-byte shared secret with a fresh random nonce.
     * Returns nonce || ciphertext || tag concatenated as one blob, so no extra storage
     * column is needed — decrypt() below expects exactly this layout.
     */
    public byte[] encryptWithSharedSecret(byte[] plaintext, byte[] sharedSecret) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            SecureRandom.getInstanceStrong().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sharedSecret, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);

            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to encrypt file content", e);
        }
    }

    /**
     * Reverses encryptWithSharedSecret(). Throws if the secret is wrong (e.g. decapsulated
     * with the wrong private key) or the stored blob was altered — AES-GCM's authentication
     * tag catches both cases, distinct from (and in addition to) the ML-DSA signature check
     * that follows this in the caller.
     */
    public byte[] decryptWithSharedSecret(byte[] ivAndCiphertext, byte[] sharedSecret) {
        try {
            byte[] iv = Arrays.copyOfRange(ivAndCiphertext, 0, GCM_IV_LENGTH_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(ivAndCiphertext, GCM_IV_LENGTH_BYTES, ivAndCiphertext.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(sharedSecret, "AES"),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to decrypt file content — wrong key or corrupted/tampered data", e);
        }
    }
}
