package com.positivity.bulkloader.internal.service;

import com.positivity.bulkloader.service.FileStorageService;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class LocalFileStorageServiceImpl implements FileStorageService {

    private final Path storageRoot;

    public LocalFileStorageServiceImpl(
            @Value("${bulk-loader.storage.local-root:/tmp/bulk-loader}") String storageRootPath) {
        this.storageRoot = Paths.get(storageRootPath);
        try {
            Files.createDirectories(this.storageRoot);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create storage root directory: " + storageRootPath, e);
        }
    }

    @Override
    public String store(@NonNull UUID jobId, @NonNull String fileName, @NonNull InputStream content, long sizeBytes) {
        try {
            Path jobDir = storageRoot.resolve(jobId.toString());
            Files.createDirectories(jobDir);
            String sanitizedName = sanitize(fileName);
            Path target = jobDir.resolve(sanitizedName);
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
            String storagePath = Paths.get(jobId.toString(), sanitizedName).toString();
            log.info("Stored file {} for job {} ({} bytes)", fileName, jobId, sizeBytes);
            return storagePath;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store file " + fileName + " for job " + jobId, e);
        }
    }

    @Override
    public InputStream retrieve(@NonNull String storagePath) {
        try {
            Path file = storageRoot.resolve(storagePath);
            return Files.newInputStream(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to retrieve file at path: " + storagePath, e);
        }
    }

    @Override
    public void delete(@NonNull String storagePath) {
        try {
            Path file = storageRoot.resolve(storagePath);
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Failed to delete file at path {}: {}", storagePath, e.getMessage());
        }
    }

    private String sanitize(String fileName) {
        return Paths.get(fileName).getFileName().toString().replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
