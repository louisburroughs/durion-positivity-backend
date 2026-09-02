package com.positivity.catalog.internal.spi.model;

import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Manifest of one feed revision from a STORE-licensed provider (sourcing plan §5.3, ADR-0053
 * shape). The provider assigns the manifest identity, so re-running an import of the same
 * revision is a recognizable no-op rather than a duplicate.
 *
 * @param importManifestId provider-assigned identity of this revision's import
 * @param sourceRevision the feed revision the chunks carry
 * @param expectedChunkCount how many chunks {@code fetchFeedChunk} can serve
 * @param expectedLineCount total lines across all chunks, for the completion check
 * @param contentChecksum provider-computed checksum of the revision's content, recorded for audit
 */
public record ProviderFeedRevision(
        @NonNull UUID importManifestId,
        @NonNull String sourceRevision,
        int expectedChunkCount,
        long expectedLineCount,
        @NonNull String contentChecksum) {}
