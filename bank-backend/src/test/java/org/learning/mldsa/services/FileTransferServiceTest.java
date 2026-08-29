package org.learning.mldsa.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.learning.mldsa.dtos.FileTransferResponse;
import org.learning.mldsa.models.FileTransfer;
import org.learning.mldsa.models.User;
import org.learning.mldsa.repositories.FileTransferRepository;
import org.learning.mldsa.repositories.UserRepositories;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the hybrid sign-then-encrypt send path and decrypt-then-verify download path
 * wired through the real {@link CryptoService} and a real {@link FileStorageService} backed
 * by a temp directory. Repositories are mocked; no Spring context or database is involved.
 */
class FileTransferServiceTest {

    private static final Long SENDER_ID = 1L;
    private static final Long RECEIVER_ID = 2L;
    private static final Long TRANSFER_ID = 100L;

    private final CryptoService crypto = new CryptoService();

    @TempDir
    private Path storageDir;

    private FileTransferRepository fileTransferRepository;
    private UserRepositories userRepositories;
    private FileTransferService service;

    private User sender;
    private User receiver;

    @BeforeEach
    void setUp() {
        fileTransferRepository = mock(FileTransferRepository.class);
        userRepositories = mock(UserRepositories.class);
        FileStorageService fileStorageService = new FileStorageService(storageDir.toString());

        service = new FileTransferService(fileTransferRepository, userRepositories,
                fileStorageService, crypto);

        sender = institution(SENDER_ID, "Alpha Bank");
        receiver = institution(RECEIVER_ID, "Beta Bank");

        when(userRepositories.findById(SENDER_ID)).thenReturn(Optional.of(sender));
        when(userRepositories.findById(RECEIVER_ID)).thenReturn(Optional.of(receiver));
        when(fileTransferRepository.save(any(FileTransfer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private User institution(Long id, String name) {
        User user = new User();
        user.setUserId(id);
        user.setName(name);

        KeyPair dsa = crypto.generateMlDsaKeyPair();
        user.setPublicKey(crypto.encodePublicKey(dsa.getPublic()));
        user.setPrivateKey(crypto.encodePrivateKey(dsa.getPrivate()));

        KeyPair kem = crypto.generateMlKemKeyPair();
        user.setKemPublicKey(crypto.encodeKemPublicKey(kem.getPublic()));
        user.setKemPrivateKey(crypto.encodeKemPrivateKey(kem.getPrivate()));
        return user;
    }

    /** Runs the send path and returns the persisted transfer, stamped with an id for lookup. */
    private FileTransfer send(byte[] content, String filename) {
        FileTransferResponse response = service.sendGeneratedFile(SENDER_ID, RECEIVER_ID, content, filename);
        assertThat(response.getSignatureValid()).isTrue();

        ArgumentCaptor<FileTransfer> captor = ArgumentCaptor.forClass(FileTransfer.class);
        verify(fileTransferRepository).save(captor.capture());
        FileTransfer saved = captor.getValue();
        saved.setTransferId(TRANSFER_ID);
        return saved;
    }

    @Test
    void sendThenDownloadRoundTripsToTheOriginalPlaintext() throws Exception {
        byte[] original = "PAYMENT ORDER: transfer 1,000,000 EUR".getBytes(StandardCharsets.UTF_8);

        FileTransfer transfer = send(original, "order.pdf");
        when(fileTransferRepository.findByTransferIdAndReceiver_UserId(TRANSFER_ID, RECEIVER_ID))
                .thenReturn(Optional.of(transfer));

        FileDownload download = service.downloadFile(TRANSFER_ID, RECEIVER_ID);

        assertThat(download.resource().getContentAsByteArray()).isEqualTo(original);
        assertThat(download.originalFilename()).isEqualTo("order.pdf");
        assertThat(transfer.getKemCiphertext()).isNotBlank();
    }

    @Test
    void whatLandsOnDiskIsCiphertextNotPlaintext() throws Exception {
        byte[] original = "SECRET".getBytes(StandardCharsets.UTF_8);

        FileTransfer transfer = send(original, "note.txt");

        byte[] stored = Files.readAllBytes(storageDir.resolve(transfer.getStoredFilename()));

        assertThat(stored).isNotEqualTo(original);
        assertThat(new String(stored, StandardCharsets.UTF_8)).doesNotContain("SECRET");
        // 12-byte GCM nonce + ciphertext + 16-byte tag == plaintext length + 28.
        assertThat(stored).hasSize(original.length + 28);
    }

    @Test
    void downloadIsRejectedForANonRecipient() {
        send("data".getBytes(StandardCharsets.UTF_8), "f.txt");
        when(fileTransferRepository.findByTransferIdAndReceiver_UserId(TRANSFER_ID, 999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.downloadFile(TRANSFER_ID, 999L))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("not the recipient");
    }

    @Test
    void tamperingWithTheStoredCiphertextIsCaughtOnDownload() throws Exception {
        FileTransfer transfer = send("legit content".getBytes(StandardCharsets.UTF_8), "f.txt");
        when(fileTransferRepository.findByTransferIdAndReceiver_UserId(TRANSFER_ID, RECEIVER_ID))
                .thenReturn(Optional.of(transfer));

        Path onDisk = storageDir.resolve(transfer.getStoredFilename());
        byte[] stored = Files.readAllBytes(onDisk);
        stored[stored.length - 1] ^= 0x01;
        Files.write(onDisk, stored);

        assertThatThrownBy(() -> service.downloadFile(TRANSFER_ID, RECEIVER_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Decryption failed");
        assertThat(transfer.getSignatureValid()).isFalse();
    }

    @Test
    void tamperingWithASignedEnvelopeFieldIsCaughtOnDownload() {
        FileTransfer transfer = send("statement".getBytes(StandardCharsets.UTF_8), "statement.pdf");
        // The file decrypts fine and its hash still matches, but the signed envelope no
        // longer does once a covered field (the filename) is altered in the record.
        transfer.setOriginalFilename("attacker-renamed.pdf");
        when(fileTransferRepository.findByTransferIdAndReceiver_UserId(TRANSFER_ID, RECEIVER_ID))
                .thenReturn(Optional.of(transfer));

        assertThatThrownBy(() -> service.downloadFile(TRANSFER_ID, RECEIVER_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Signature verification failed");
        assertThat(transfer.getSignatureValid()).isFalse();
    }

    @Test
    void sendingToYourselfIsRejected() {
        assertThatThrownBy(() -> service.sendGeneratedFile(SENDER_ID, SENDER_ID,
                "x".getBytes(StandardCharsets.UTF_8), "f.txt"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("must be different");
    }

    @Test
    void sendingToAReceiverWithoutAKemKeyIsRejected() {
        receiver.setKemPublicKey(null);

        assertThatThrownBy(() -> service.sendGeneratedFile(SENDER_ID, RECEIVER_ID,
                "x".getBytes(StandardCharsets.UTF_8), "f.txt"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("ML-KEM key pair");
    }
}
