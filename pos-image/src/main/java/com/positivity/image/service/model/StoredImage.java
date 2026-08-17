package com.positivity.image.service.model;

import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A reference to an image this service holds (CAP-324 #1257).
 *
 * <p>What a consumer needs to render or re-request the image, and nothing about where the bytes
 * physically live. That the store is a database table today and might be object storage tomorrow is
 * not a caller's concern, and a reference that leaked it would have to change when the store did.
 *
 * @param imageId     this service's identifier, stable across re-ingests of the same content
 * @param filename    the name it was stored under
 * @param contentType MIME type of the stored bytes
 * @param byteSize    size of the stored bytes
 * @param contentHash SHA-256 of the bytes, lower-case hex. Two references with the same hash are
 *                    the same picture, which is how a consumer avoids re-fetching one it has
 * @param sourceUri   where the bytes were fetched from, when they were fetched from somewhere.
 *                    Provenance only — never a render source, because rendering from it puts a
 *                    third party in the path of every page view
 * @param newlyStored whether this call stored the bytes, or found them already held
 */
public record StoredImage(
        long imageId,
        @NonNull String filename,
        @NonNull String contentType,
        long byteSize,
        @NonNull String contentHash,
        @Nullable String sourceUri,
        boolean newlyStored) {

    public StoredImage {
        Objects.requireNonNull(filename, "filename must not be null");
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(contentHash, "contentHash must not be null");
    }
}
