package org.learning.mldsa.services;

import org.learning.mldsa.models.User;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * ML-DSA-65 key generation, envelope signing, and verification, plus SHA-384 file hashing.
 *
 * ML-DSA (FIPS 204, the standardised form of CRYSTALS-Dilithium) is a lattice-based
 * signature scheme, chosen here because it is not broken by Shor's algorithm. That is the
 * whole point of the move: Ed25519, which this replaced, is elliptic-curve, and an
 * elliptic-curve public key *is* the hard problem a quantum computer solves — recovering
 * the private key from the public one, and with it the ability to forge any signature the
 * system would accept. Every signature here is meant to still mean something in a decade.
 *
 * No third-party dependency and no provider to register: ML-DSA is in the JDK itself from
 * Java 24 (JEP 497), served by the built-in SUN provider, the same way Ed25519 was.
 *
 * The practical cost is size, and it is not small. Base64-encoded, an ML-DSA-65 public key
 * is 2,632 characters against Ed25519's 60, and a signature is 4,412 against 88 — roughly
 * fifty times larger. That is why every column holding a key or a signature is TEXT rather
 * than varchar(255); see V8__mldsa_key_and_signature_widths.sql. The private key is the
 * exception: the JDK encodes it as a 32-byte seed, so it is *smaller* than Ed25519's was.
 *
 * ML-DSA-65 is NIST security level 3 — the balanced parameter set. ML-DSA-44 and -87 exist
 * either side of it; changing this constant alone would switch schemes, but every existing
 * key and signature would stop verifying, because parameter sets are not interchangeable.
 *
 * Keys are NOT interchangeable between schemes either. An account provisioned under
 * Ed25519 cannot sign or verify here; it needs a freshly generated ML-DSA-65 key pair, and
 * signatures produced under the old scheme cannot be verified at all. There is no
 * dual-scheme path here by design — the database is rebuilt from scratch instead.
 */
@Service
public class CryptoService {

    private static final String ALGORITHM = "ML-DSA-65";

    public KeyPair generateSigningKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to generate " + ALGORITHM + " key pair", e);
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
            KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
            return factory.generatePublic(new X509EncodedKeySpec(bytes));
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to decode " + ALGORITHM + " public key", e);
        }
    }

    public PrivateKey decodePrivateKey(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
            return factory.generatePrivate(new PKCS8EncodedKeySpec(bytes));
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to decode " + ALGORITHM + " private key", e);
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
     *
     * Unaffected by the move to ML-DSA: what gets signed is independent of which
     * algorithm signs it.
     */
    public String buildEnvelope(Long senderId, Long receiverId, String fileHash,
                                 String originalFilename, long sentAtEpochMilli) {
        return senderId + "|" + receiverId + "|" + fileHash + "|" + originalFilename + "|" + sentAtEpochMilli;
    }

    /**
     * The envelope for a transfer that carries an ISO 20022 payload beside its PDF.
     *
     * Extends the original with the transfer's UETR and the canonical hash of the XML, so
     * one signature covers both artefacts. Signing them separately would leave the two
     * swappable relative to each other: a valid PDF signature and a valid XML signature
     * from two different transfers could be presented as one, with the documents disagreeing
     * about the amount. Binding both into a single envelope makes that combination
     * unverifiable.
     *
     * Kept as a separate method rather than adding parameters to the original, because a
     * transfer with no XML must still rebuild the exact string that was signed for it.
     * Every transfer sent before this existed, and every direct upload, has no XML hash —
     * folding a null into the original envelope would have invalidated all of their
     * signatures. Which form applies is decided by whether an XML hash was persisted, so
     * reconstruction stays deterministic.
     */
    public String buildCombinedEnvelope(Long senderId, Long receiverId, String fileHash,
                                         String originalFilename, long sentAtEpochMilli,
                                         String uetr, String xmlHash) {
        return buildEnvelope(senderId, receiverId, fileHash, originalFilename, sentAtEpochMilli)
                + "|" + uetr + "|" + xmlHash;
    }

    /**
     * Canonical signed form of a payment: who paid, who was paid, how much, and when.
     *
     * Accounts are identified by account number rather than database id — the number is
     * the account's real-world identity, and it stays meaningful to anyone re-checking
     * this signature later without access to this system's internal keys.
     *
     * The amount is normalised to two decimal places by canonicalAmount below, so the
     * same sum always produces the same envelope regardless of how the caller wrote it.
     */
    public String buildPaymentEnvelope(String fromAccountNumber, String toAccountNumber,
                                        BigDecimal amount, long timestampEpochMilli) {
        return fromAccountNumber + "|" + toAccountNumber + "|"
                + canonicalAmount(amount) + "|" + timestampEpochMilli;
    }

    /**
     * Canonical signed form of a statement.
     *
     * Covers the figures a statement asserts — whose account, which period, what it opened
     * and closed at — plus when it was produced, so two statements over the same period
     * generated at different times are distinguishable rather than interchangeable.
     *
     * generatedAt is the ISO-8601 instant exactly as printed on the document, so a verifier
     * can rebuild this string from the page in front of them without having to guess at a
     * format or a timezone.
     */
    public String buildStatementEnvelope(String accountNumber, String periodFrom, String periodTo,
                                          BigDecimal openingBalance, BigDecimal closingBalance,
                                          String generatedAtIso) {
        return accountNumber + "|" + periodFrom + "|" + periodTo + "|"
                + canonicalAmount(openingBalance) + "|" + canonicalAmount(closingBalance) + "|"
                + generatedAtIso;
    }

    /**
     * The signing key of the account this document belongs to.
     *
     * Shared rather than repeated: every document the system signs is signed with its
     * owner's own key, and the check for a missing key belongs with the decoding.
     */
    public PrivateKey signingKeyOf(User owner) {
        if (owner.getPrivateKey() == null || owner.getPrivateKey().isBlank()) {
            throw new RuntimeException("Account owner does not have a signing key pair provisioned");
        }
        return decodePrivateKey(owner.getPrivateKey());
    }

    /**
     * Canonical signed form of an interbank settlement message.
     *
     * Binds the payment's identity, the two banks, the amount and the hash of the message
     * itself into one signature, so "this pacs.008, for this transfer, between these two
     * agents" cannot be recombined: a valid message from one payment cannot be presented as
     * the instruction for another.
     *
     * A separate builder rather than an extension of an existing one, for the reason
     * buildCombinedEnvelope is separate — every envelope this system has ever signed must
     * stay rebuildable in exactly the form it was signed.
     */
    public String buildSettlementMessageEnvelope(String uetr, String debtorAgentCode, String creditorAgentCode,
                                                  BigDecimal amount, String xmlHash, long createdAtEpochMilli) {
        return uetr + "|" + debtorAgentCode + "|" + creditorAgentCode + "|"
                + canonicalAmount(amount) + "|" + xmlHash + "|" + createdAtEpochMilli;
    }

    /**
     * Canonical signed form of a deposit — same shape, with no counterparty.
     *
     * Kept for deposits recorded before deposits were a teller operation. They were signed in
     * exactly this form with the account holder's key, and rebuilding their envelope any other
     * way would fail to verify against the signature actually stored.
     *
     * @deprecated new deposits use {@link #buildTellerDepositEnvelope}, which names the
     * institution that took the money and is signed by it.
     */
    @Deprecated
    public String buildDepositEnvelope(String accountNumber, BigDecimal amount, long timestampEpochMilli) {
        return accountNumber + "|" + canonicalAmount(amount) + "|" + timestampEpochMilli;
    }

    /**
     * Canonical signed form of a deposit taken at the counter.
     *
     * Names the institution as well as the account, because the institution is what this
     * signature asserts: this bank received this money and credited this account. Without it
     * two banks' deposits of the same amount into the same account at the same moment would
     * produce identical envelopes, and neither signature would say who took the cash.
     *
     * A separate builder rather than an extra parameter on the original, for the reason
     * buildCombinedEnvelope is separate: every envelope this system has signed must stay
     * rebuildable in the form it was signed.
     */
    public String buildTellerDepositEnvelope(String accountNumber, String institutionCode,
                                              BigDecimal amount, long timestampEpochMilli) {
        return accountNumber + "|" + institutionCode + "|"
                + canonicalAmount(amount) + "|" + timestampEpochMilli;
    }

    /**
     * Renders an amount the one way it is ever signed.
     *
     * Without this, 100.5 and 100.50 are the same money but different strings, so a
     * signature produced over one would fail to verify when the envelope was later
     * rebuilt from the other. Scaling is exact: an amount carrying more precision than
     * currency allows is rejected rather than silently rounded into a different sum.
     */
    private String canonicalAmount(BigDecimal amount) {
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException e) {
            throw new RuntimeException("Amount must have at most 2 decimal places", e);
        }
    }

    public String sign(String payload, PrivateKey privateKey) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(privateKey);
            signature.update(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to sign transfer envelope", e);
        }
    }

    public boolean verify(String payload, String signatureBase64, PublicKey publicKey) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(payload.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to verify transfer signature", e);
        }
    }
}
