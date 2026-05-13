package com.positivity.bulkloader.internal.service;

import com.positivity.bulkloader.internal.entity.TusUpload;
import com.positivity.bulkloader.internal.exception.TusOffsetConflictException;
import com.positivity.bulkloader.internal.exception.TusUploadExpiredException;
import com.positivity.bulkloader.internal.repository.TusUploadRepository;
import com.positivity.bulkloader.service.BulkLoadJobService;
import com.positivity.bulkloader.service.TusUploadService;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class TusUploadServiceImpl implements TusUploadService {

    private final TusUploadRepository tusUploadRepository;
    private final BulkLoadJobService bulkLoadJobService;
    private final Path storageRoot;
    private final Path tusRoot;
    private final int expiryHours;

    public TusUploadServiceImpl(
            TusUploadRepository tusUploadRepository,
            BulkLoadJobService bulkLoadJobService,
            @Value("${bulk-loader.storage.local-root:/tmp/bulk-loader}") String storageRootPath,
            @Value("${bulk-loader.tus.expiry-hours:24}") int expiryHours) {
        this.tusUploadRepository = tusUploadRepository;
        this.bulkLoadJobService = bulkLoadJobService;
        this.storageRoot = Paths.get(storageRootPath);
        this.tusRoot = this.storageRoot.resolve(".tus");
        this.expiryHours = expiryHours;
        try {
            Files.createDirectories(this.tusRoot);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create TUS temp directory: " + this.tusRoot, e);
        }
    }

    @Override
    @Transactional
    public Created createUpload(
            @NonNull UUID jobId, @NonNull String fileName, long totalSize, @NonNull String operatorId) {
        TusUpload upload = new TusUpload();
        upload.setJobId(jobId);
        upload.setOperatorId(operatorId);
        upload.setFileName(sanitize(fileName));
        upload.setTotalSize(totalSize);
        upload.setUploadOffset(0L);
        upload.setCompleted(false);
        upload.setExpiresAt(Instant.now().plus(expiryHours, ChronoUnit.HOURS));
        TusUpload saved = tusUploadRepository.save(upload);

        try {
            Files.createFile(tusRoot.resolve(saved.getId().toString()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create TUS temp file for upload " + saved.getId(), e);
        }
        log.info(
                "TUS upload created: id={} jobId={} fileName={} totalSize={}",
                saved.getId(),
                jobId,
                saved.getFileName(),
                totalSize);
        return new Created(saved.getId(), saved.getExpiresAt());
    }

    @Override
    public Info getInfo(@NonNull UUID uploadId) {
        TusUpload upload = findOrThrow(uploadId);
        return new Info(upload.getUploadOffset(), upload.getTotalSize(), upload.getExpiresAt(), upload.isCompleted());
    }

    @Override
    @Transactional
    public long appendChunk(@NonNull UUID uploadId, long expectedOffset, @NonNull InputStream data, long chunkLength)
            throws IOException {
        TusUpload upload = findOrThrow(uploadId);

        if (upload.isCompleted()) {
            throw new IllegalStateException("Upload is already complete: " + uploadId);
        }
        if (Instant.now().isAfter(upload.getExpiresAt())) {
            throw new TusUploadExpiredException(uploadId);
        }
        if (upload.getUploadOffset() != expectedOffset) {
            throw new TusOffsetConflictException(expectedOffset, upload.getUploadOffset());
        }

        Path tempFile = tusRoot.resolve(uploadId.toString());
        long bytesWritten;
        try (var out = Files.newOutputStream(tempFile, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
            bytesWritten = data.transferTo(out);
        }

        long newOffset = expectedOffset + bytesWritten;
        int updated = tusUploadRepository.advanceOffset(uploadId, expectedOffset, newOffset);
        if (updated == 0) {
            throw new TusOffsetConflictException(expectedOffset, upload.getUploadOffset());
        }

        if (newOffset >= upload.getTotalSize()) {
            finalizeUpload(upload, newOffset);
        }
        return newOffset;
    }

    @Override
    @Transactional
    public void deleteUpload(@NonNull UUID uploadId) {
        TusUpload upload = findOrThrow(uploadId);
        deleteTempFile(uploadId);
        tusUploadRepository.delete(upload);
        log.info("TUS upload deleted: id={}", uploadId);
    }

    @Scheduled(fixedDelayString = "${bulk-loader.tus.cleanup-interval-ms:3600000}")
    @Transactional
    public void cleanupExpiredUploads() {
        List<TusUpload> expired = tusUploadRepository.findByExpiresAtBeforeAndCompletedFalse(Instant.now());
        for (TusUpload upload : expired) {
            try {
                deleteTempFile(upload.getId());
                tusUploadRepository.delete(upload);
                log.info("Cleaned up expired TUS upload: id={} jobId={}", upload.getId(), upload.getJobId());
            } catch (Exception e) {
                log.warn("Failed to clean up expired TUS upload {}: {}", upload.getId(), e.getMessage());
            }
        }
        if (!expired.isEmpty()) {
            log.info("TUS cleanup complete: {} expired uploads removed", expired.size());
        }
    }

    private void finalizeUpload(TusUpload upload, long finalOffset) throws IOException {
        Path tempFile = tusRoot.resolve(upload.getId().toString());
        Path jobDir = storageRoot.resolve(upload.getJobId().toString());
        Files.createDirectories(jobDir);
        Path dest = jobDir.resolve(upload.getFileName());
        Files.move(tempFile, dest, StandardCopyOption.REPLACE_EXISTING);
        String storagePath = storageRoot.relativize(dest).toString();
        bulkLoadJobService.markUploadStored(upload.getJobId(), upload.getOperatorId(), storagePath);
        upload.setCompleted(true);
        tusUploadRepository.save(upload);
        log.info(
                "TUS upload finalized: id={} jobId={} size={} dest={}",
                upload.getId(),
                upload.getJobId(),
                finalOffset,
                dest);
    }

    private void deleteTempFile(UUID uploadId) {
        try {
            Files.deleteIfExists(tusRoot.resolve(uploadId.toString()));
        } catch (IOException e) {
            log.warn("Failed to delete TUS temp file for upload {}: {}", uploadId, e.getMessage());
        }
    }

    private TusUpload findOrThrow(UUID uploadId) {
        return tusUploadRepository
                .findById(uploadId)
                .orElseThrow(() -> new NoSuchElementException("TUS upload not found: " + uploadId));
    }

    private String sanitize(String fileName) {
        return Paths.get(fileName).getFileName().toString().replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
