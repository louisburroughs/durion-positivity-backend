package com.positivity.catalog.internal.spi.model;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * One chunk of a feed revision (sourcing plan §5.3).
 *
 * @param importManifestId the manifest the chunk belongs to
 * @param chunkSequence 1-based position within the revision
 * @param lines the chunk's labor-time lines
 */
public record ProviderFeedChunk(
        @NonNull UUID importManifestId,
        int chunkSequence,
        @NonNull List<ProviderFeedLine> lines) {}
