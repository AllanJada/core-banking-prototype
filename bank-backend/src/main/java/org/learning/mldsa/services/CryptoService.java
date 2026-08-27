package org.learning.mldsa.services;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * ML-DSA-65 (FIPS 204) key generation, envelope signing, and verification, plus SHA-384
 * file hashing. Requires Bouncy Castle's "BC" provider on the classpath — this is NOT
 * available in the plain JDK.
 *
 * REQUIRED DEPENDENCY (add to pom.xml, not included here):
 *   org.bouncycastle:bcprov-jdk18on, version 1.78 or later.
 *   Earlier Bouncy Castle versions only expose the pre-standardization experimental
 *   "Dilithium" API under a different artifact (bcpqc-jdk18on) — that is NOT the same
 *   as "ML-DSA-65" and will not work with the algorithm name used below.
 *
 * NOT VERIFIED BY COMPILATION — written without access to Maven or the Bouncy Castle
 * jars in the environment this was authored in. Review carefully and run the
 * verification steps (register two users, send a file, download it) before relying
 * on this.
 */
@Service
public class CryptoService {

    private static final String ALGORITHM = "ML-DSA-65";
    private static final String PROVIDER = "BC";

    static {
        if (Security.getProvider(PROVIDER) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public KeyPair generateMlDsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM, PROVIDER);
            return generator.generateKeyPair();
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
            KeyFactory factory = KeyFactory.getInstance(ALGORITHM, PROVIDER);
            return factory.generatePublic(new X509EncodedKeySpec(bytes));
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to decode ML-DSA public key", e);
        }
    }

    public PrivateKey decodePrivateKey(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            KeyFactory factory = KeyFactory.getInstance(ALGORITHM, PROVIDER);
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
     * Canonical, order-sensitive string representation of a transfer's identity. This
     * exact string is what gets signed at send time and rebuilt (from the same
     * persisted values, never recomputed fresh) to verify at download time — changing
     * the field order or separator here invalidates every previously-issued signature.
     */
    public String buildEnvelope(Long senderId, Long receiverId, String fileHash,
                                 String originalFilename, long sentAtEpochMilli) {
        return senderId + "|" + receiverId + "|" + fileHash + "|" + originalFilename + "|" + sentAtEpochMilli;
    }

    public String sign(String payload, PrivateKey privateKey) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM, PROVIDER);
            signature.initSign(privateKey);
            signature.update(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to sign transfer envelope", e);
        }
    }

    public boolean verify(String payload, String signatureBase64, PublicKey publicKey) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM, PROVIDER);
            signature.initVerify(publicKey);
            signature.update(payload.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to verify transfer signature", e);
        }
    }
}
