package com.positivity.referencemock.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Manifest describing the current feed revision for the chunked-manifest import (plan §5.3,
 * ADR-0053 shape). Deterministic: the id is fixed per revision in the fixture file, the counts
 * are computed from the fixture rows, and the checksum is SHA-256 hex over the canonical line
 * serialization documented on the manifest endpoint.
 *
 * @param importManifestId producer-assigned manifest id, stable per revision
 * @param sourceRevision feed revision this manifest describes
 * @param expectedChunkCount number of chunks the consumer must fetch
 * @param expectedLineCount total labor-time lines across all chunks
 * @param contentChecksum SHA-256 hex of the canonical concatenation of all lines
 */
@Schema(description = "Feed manifest for the chunked-manifest import of the current revision.")
public record FeedManifestDto(
        @Schema(description = "Producer-assigned manifest id, stable per revision")
        UUID importManifestId,

        @Schema(description = "Feed revision", example = "2026-09-01")
        String sourceRevision,

        @Schema(description = "Number of chunks to fetch", example = "6")
        int expectedChunkCount,

        @Schema(description = "Total lines across all chunks", example = "294")
        int expectedLineCount,

        @Schema(description = "SHA-256 hex of the canonical concatenation of all lines")
        String contentChecksum) {}
