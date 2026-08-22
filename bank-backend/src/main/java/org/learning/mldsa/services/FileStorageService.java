package org.learning.mldsa.services;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Handles reading and writing files on disk.
 *
 * Deliberately never trusts a client-supplied filename as a storage path (path traversal /
 * overwrite risk) — every file is stored under a server-generated UUID name. The original
 * filename is kept only as metadata (see FileTransfer.originalFilename) for display purposes.
 */
@Service
public class FileStorageService {

    private final Path storageRoot;

    public FileStorageService(@Value("${app.file-storage-path}") String storagePath) {
        this.storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(storageRoot);
        } catch (IOException e) {
            throw new RuntimeException("Could not initialize file storage directory: " + storageRoot, e);
        }
    }

    /**
     * Persists the given file under a new server-generated name.
     *
     * @return the stored filename (not the original filename) — this is what gets saved
     *         alongside the transfer record and used to locate the file later.
     */
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new RuntimeException("Cannot store an empty file");
        }

        String extension = extractExtension(file.getOriginalFilename());
        String storedFilename = UUID.randomUUID() + extension;

        Path target = storageRoot.resolve(storedFilename).normalize();

        // Defense in depth: even though storedFilename is always our own UUID, confirm it
        // still resolves inside storageRoot before writing.
        if (!target.getParent().equals(storageRoot)) {
            throw new RuntimeException("Invalid storage path");
        }

        try (var inputStream = file.getInputStream()) {
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file", e);
        }

        return storedFilename;
    }

    public Resource loadAsResource(String storedFilename) {
        try {
            Path filePath = storageRoot.resolve(storedFilename).normalize();

            if (!filePath.getParent().equals(storageRoot)) {
                throw new RuntimeException("Invalid file reference");
            }

            Resource resource = new UrlResource(filePath.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new RuntimeException("File not found on disk: " + storedFilename);
            }
            return resource;
        } catch (MalformedURLException e) {
            throw new RuntimeException("Failed to load file: " + storedFilename, e);
        }
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
