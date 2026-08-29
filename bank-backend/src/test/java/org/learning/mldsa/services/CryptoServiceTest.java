package org.learning.mldsa.services;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the JDK-native ML-DSA-65 / ML-KEM-768 / AES-256-GCM implementation end-to-end,
 * including the encode/decode round-trips that mirror exactly how User and FileTransfer
 * persist these fields, plus the negative cases (wrong key, tampered ciphertext).
 *
 * Pure JUnit — no Spring context, no database — so it runs anywhere a JDK with JEP 496/497
 * (finalized in JDK 24) is present.
 */
class CryptoServiceTest {

    private final CryptoService crypto = new CryptoService();

    // ---- ML-DSA-65 : signing ----

    @Test
    void mlDsaKeysSurviveBase64RoundTripAndStillSignAndVerify() {
        KeyPair pair = crypto.generateMlDsaKeyPair();

        String encodedPublic = crypto.encodePublicKey(pair.getPublic());
        String encodedPrivate = crypto.encodePrivateKey(pair.getPrivate());

        PublicKey decodedPublic = crypto.decodePublicKey(encodedPublic);
        PrivateKey decodedPrivate = crypto.decodePrivateKey(encodedPrivate);

        String payload = "1|2|deadbeef|statement.pdf|1724930000000";
        String signature = crypto.sign(payload, decodedPrivate);

        assertThat(crypto.verify(payload, signature, decodedPublic)).isTrue();
    }

    @Test
    void verifyRejectsTamperedPayload() {
        KeyPair pair = crypto.generateMlDsaKeyPair();
        String signature = crypto.sign("original payload", pair.getPrivate());

        assertThat(crypto.verify("tampered payload", signature, pair.getPublic())).isFalse();
    }

    @Test
    void verifyRejectsSignatureFromADifferentKey() {
        KeyPair signer = crypto.generateMlDsaKeyPair();
        KeyPair impostor = crypto.generateMlDsaKeyPair();

        String signature = crypto.sign("payload", signer.getPrivate());

        assertThat(crypto.verify("payload", signature, impostor.getPublic())).isFalse();
    }

    // ---- SHA-384 hashing / envelope ----

    @Test
    void hashFileIsDeterministicLowercaseHexSha384() {
        byte[] content = "bank wire instruction".getBytes(StandardCharsets.UTF_8);

        String first = crypto.hashFile(content);
        String second = crypto.hashFile(content);

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(96).matches("[0-9a-f]+");
        assertThat(crypto.hashFile("different".getBytes(StandardCharsets.UTF_8))).isNotEqualTo(first);
    }

    @Test
    void buildEnvelopeIsOrderSensitiveAndPipeDelimited() {
        String envelope = crypto.buildEnvelope(7L, 9L, "abc123", "invoice.pdf", 1724930000000L);

        assertThat(envelope).isEqualTo("7|9|abc123|invoice.pdf|1724930000000");
    }

    // ---- ML-KEM-768 : encapsulation ----

    @Test
    void mlKemKeysSurviveBase64RoundTripAndDeriveTheSameSharedSecret() {
        KeyPair pair = crypto.generateMlKemKeyPair();

        PublicKey decodedPublic = crypto.decodeKemPublicKey(crypto.encodeKemPublicKey(pair.getPublic()));
        PrivateKey decodedPrivate = crypto.decodeKemPrivateKey(crypto.encodeKemPrivateKey(pair.getPrivate()));

        CryptoService.Encapsulation encapsulation = crypto.encapsulate(decodedPublic);
        byte[] recovered = crypto.decapsulate(decodedPrivate, encapsulation.kemCiphertext());

        assertThat(encapsulation.sharedSecret()).hasSize(32);
        assertThat(recovered).isEqualTo(encapsulation.sharedSecret());
    }

    @Test
    void decapsulatingWithTheWrongPrivateKeyYieldsADifferentSecret() {
        KeyPair receiver = crypto.generateMlKemKeyPair();
        KeyPair stranger = crypto.generateMlKemKeyPair();

        CryptoService.Encapsulation encapsulation = crypto.encapsulate(receiver.getPublic());
        byte[] wrong = crypto.decapsulate(stranger.getPrivate(), encapsulation.kemCiphertext());

        // FIPS 203 implicit rejection: no exception, just a secret that will not decrypt.
        assertThat(wrong).hasSize(32).isNotEqualTo(encapsulation.sharedSecret());
    }

    // ---- AES-256-GCM keyed by the KEM secret ----

    @Test
    void aesGcmRoundTripsThroughTheFullKemDerivedKey() {
        KeyPair receiver = crypto.generateMlKemKeyPair();
        CryptoService.Encapsulation encapsulation = crypto.encapsulate(receiver.getPublic());
        byte[] secret = crypto.decapsulate(receiver.getPrivate(), encapsulation.kemCiphertext());

        byte[] plaintext = "confidential settlement file".getBytes(StandardCharsets.UTF_8);
        byte[] blob = crypto.encryptWithSharedSecret(plaintext, encapsulation.sharedSecret());

        assertThat(blob).isNotEqualTo(plaintext);
        assertThat(crypto.decryptWithSharedSecret(blob, secret)).isEqualTo(plaintext);
    }

    @Test
    void decryptFailsWithTheWrongSecret() {
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 1);
        byte[] wrongSecret = new byte[32];
        Arrays.fill(wrongSecret, (byte) 2);

        byte[] blob = crypto.encryptWithSharedSecret("data".getBytes(StandardCharsets.UTF_8), secret);

        assertThatThrownBy(() -> crypto.decryptWithSharedSecret(blob, wrongSecret))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void decryptFailsWhenTheCiphertextIsTampered() {
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 7);

        byte[] blob = crypto.encryptWithSharedSecret("data".getBytes(StandardCharsets.UTF_8), secret);
        blob[blob.length - 1] ^= 0x01; // flip a bit in the GCM tag region

        assertThatThrownBy(() -> crypto.decryptWithSharedSecret(blob, secret))
                .isInstanceOf(RuntimeException.class);
    }
}
