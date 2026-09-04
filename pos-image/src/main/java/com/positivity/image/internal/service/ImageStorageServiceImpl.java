package com.positivity.image.internal.service;

import com.positivity.image.internal.entity.ImageContentEntity;
import com.positivity.image.internal.entity.ImageEntity;
import com.positivity.image.internal.exception.ImageValidationException;
import com.positivity.image.internal.repository.ImageContentRepository;
import com.positivity.image.internal.repository.ImageRepository;
import com.positivity.image.internal.service.model.StoredImage;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storing image bytes, content-addressed (CAP-324 #1257).
 *
 * <h2>Two levels of "already have it"</h2>
 *
 * The bytes are keyed by their own SHA-256, so identical artwork from two manufacturers occupies one
 * row. The <em>record</em> is keyed by origin and content together, so re-ingesting the same picture
 * from the same URL returns the same id rather than a new one.
 *
 * <p>Both are needed. Deduplicating only the bytes would still hand a consumer a fresh id on every
 * catalogue refresh, which defeats the point — a consumer that cannot tell "unchanged" from "new"
 * re-renders and re-caches everything each cycle.
 *
 * <h2>Empty content is refused</h2>
 *
 * An empty body is what a failed download looks like. Storing it would satisfy every later
 * "do we already have this?" check, and the missing artwork would never be retried.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageStorageServiceImpl implements ImageStorageService {

    private final ImageRepository imageRepository;
    private final ImageContentRepository contentRepository;
    private final Clock clock;

    @Override
    @NonNull
    @Transactional
    public StoredImage store(
            @NonNull String filename,
            @NonNull String contentType,
            byte[] content,
            @Nullable String sourceUri,
            @Nullable List<String> context) {
        if (content == null || content.length == 0) {
            throw new ImageValidationException("refusing to store an empty image for '" + filename
                    + "': an empty body is a failed download, and storing it would stop it ever being retried");
        }

        String contentHash = sha256(content);

        Optional<ImageEntity> existing = sourceUri == null
                ? Optional.empty()
                : imageRepository.findFirstBySourceUriAndContentHash(sourceUri, contentHash);
        if (existing.isPresent()) {
            return toReference(existing.get(), false);
        }

        storeContentOnce(contentHash, contentType, content);

        ImageEntity record = new ImageEntity();
        record.setFilename(filename);
        record.setContentHash(contentHash);
        record.setContentType(contentType);
        record.setByteSize((long) content.length);
        record.setSourceUri(sourceUri);
        record.setContext(context == null ? List.of() : List.copyOf(context));
        // The url column is what the legacy read path resolves as a filesystem path. Left null:
        // this record's bytes are held here, and inventing a path would send the reader looking for
        // a file that does not exist.
        record.setUrl(null);

        ImageEntity saved = imageRepository.save(record);
        log.debug("Stored image {} ({} bytes) from {}", saved.getId(), content.length, sourceUri);
        return toReference(saved, true);
    }

    /**
     * Writes the bytes unless they are already held.
     *
     * <p>The insert can lose a race with a concurrent ingest of the same artwork — two catalogue
     * runs, or two instances. That collision means the content is present, which is the outcome
     * wanted, so it is treated as success rather than failing an ingest over a duplicate.
     */
    private void storeContentOnce(String contentHash, String contentType, byte[] content) {
        if (contentRepository.existsById(contentHash)) {
            return;
        }
        ImageContentEntity blob = new ImageContentEntity();
        blob.setContentHash(contentHash);
        blob.setContentType(contentType);
        blob.setByteSize(content.length);
        blob.setContent(content);
        blob.setCreatedAt(Instant.now(clock));
        try {
            contentRepository.save(blob);
        } catch (DataIntegrityViolationException e) {
            log.debug("Image content {} was stored concurrently; keeping the existing copy", contentHash);
        }
    }

    private static StoredImage toReference(ImageEntity record, boolean newlyStored) {
        return new StoredImage(
                record.getId(),
                record.getFilename(),
                record.getContentType() == null ? "application/octet-stream" : record.getContentType(),
                record.getByteSize() == null ? 0L : record.getByteSize(),
                record.getContentHash(),
                record.getSourceUri(),
                newlyStored);
    }

    @NonNull
    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every Java platform; if it is absent the deployment is broken
            // in a way no fallback would improve.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
