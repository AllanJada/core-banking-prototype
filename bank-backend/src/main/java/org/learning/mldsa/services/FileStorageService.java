package org.learning.mldsa.services;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Handles reading and writing files on disk.
 *
 * Deliberately never trusts a client-supplied filename as a storage path (path traversal /
 * overwrite risk) — every file is stored under a server-generated UUID name. The original
 * filename is kept only as metadata (see FileTransfer.originalFilename) for display purposes.
 *
 * Content is encrypted on the way to disk and decrypted on the way back, so callers only
 * ever see plaintext. Confining encryption to this one class is what keeps it invisible to
 * everything else: hashes and signatures are computed over plaintext held in memory before
 * a file is ever written, and re-verification reads plaintext back, so introducing
 * encryption changed neither and every signature issued beforehand remains valid.
 */
@Service
public class FileStorageService {

    private final Path storageRoot;
    private final FileEncryptionService fileEncryptionService;

    public FileStorageService(@Value("${app.file-storage-path}") String storagePath,
                              FileEncryptionService fileEncryptionService) {
        this.fileEncryptionService = fileEncryptionService;
        this.storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(storageRoot);
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize file storage directory: " + storageRoot, e);
        }
    }

    /**
     * Persists an uploaded file under a new server-generated name.
     *
     * @return the stored filename (not the original filename) — this is what gets saved
     *         alongside the transfer record and used to locate the file later.
     */
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new RuntimeException("Cannot store an empty file");
        }

        try (var inputStream = file.getInputStream()) {
            return writeNewFile(inputStream, file.getOriginalFilename());
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file", e);
        }
    }

    /**
     * Persists server-generated content (e.g. a rendered PDF) that never existed as a
     * client-supplied file at all — same UUID-naming and path-safety guarantees as the
     * MultipartFile path above, just without needing to wrap bytes in a fake upload.
     */
    public String store(byte[] content, String originalFilename) {
        if (content == null || content.length == 0) {
            throw new RuntimeException("Cannot store empty content");
        }

        try (var inputStream = new java.io.ByteArrayInputStream(content)) {
            return writeNewFile(inputStream, originalFilename);
        } catch (IOException e) {
            throw new RuntimeException("Failed to store generated file", e);
        }
    }

    private String writeNewFile(java.io.InputStream inputStream, String originalFilename) throws IOException {
        String extension = extractExtension(originalFilename);
        String storedFilename = UUID.randomUUID() + extension;

        Path target = resolveInsideRoot(storedFilename, "Invalid storage path");

        // Read fully, then encrypt: AES-GCM authenticates the whole message, so it works on
        // a complete buffer rather than a stream. That caps a stored file at what fits in
        // memory, which the multipart upload limit already bounds.
        byte[] plaintext = inputStream.readAllBytes();
        Files.write(target, fileEncryptionService.encrypt(plaintext));

        return storedFilename;
    }

    /**
     * Loads a stored file's plaintext.
     *
     * Returns the decrypted bytes in memory rather than a handle to the file on disk, since
     * what is on disk is ciphertext and no caller wants that. Files written before
     * encryption existed are still plaintext and are passed through unchanged — see
     * FileEncryptionService.decrypt.
     */
    public Resource loadAsResource(String storedFilename) {
        Path filePath = resolveInsideRoot(storedFilename, "Invalid file reference");

        if (!Files.isReadable(filePath)) {
            throw new RuntimeException("File not found on disk: " + storedFilename);
        }

        try {
            byte[] stored = Files.readAllBytes(filePath);
            return new ByteArrayResource(fileEncryptionService.decrypt(stored));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load file: " + storedFilename, e);
        }
    }

    /**
     * Resolves a stored name inside the storage root.
     *
     * Defence in depth: the name is always a UUID this service generated, but confirming it
     * still lands inside the root costs nothing and means a future caller passing something
     * else cannot escape the directory.
     */
    private Path resolveInsideRoot(String storedFilename, String message) {
        Path filePath = storageRoot.resolve(storedFilename).normalize();
        if (!filePath.getParent().equals(storageRoot)) {
            throw new RuntimeException(message);
        }
        return filePath;
    }

    // Only keeps a short, extension-like suffix (e.g. ".pdf") — never any path separators.
    private String extractExtension(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        String cleaned = Path.of(originalFilename).getFileName().toString();
        int dotIndex = cleaned.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == cleaned.length() - 1) {
            return "";
        }
        String extension = cleaned.substring(dotIndex);
        // Guard against absurdly long "extensions" someone crafted as a filename.
        return extension.length() <= 10 ? extension : "";
    }
}
