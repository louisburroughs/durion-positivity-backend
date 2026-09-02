package com.positivity.referencemock.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/**
 * One chunk of the labor-time feed, at most 50 lines, fetched by 1-based sequence number.
 *
 * @param importManifestId manifest the chunk belongs to
 * @param chunkSequence 1-based chunk sequence
 * @param lines the labor-time lines in canonical feed order
 */
@Schema(description = "One chunk of the labor-time feed (max 50 lines).")
public record FeedChunkDto(
        @Schema(description = "Manifest the chunk belongs to")
        UUID importManifestId,

        @Schema(description = "1-based chunk sequence", example = "1")
        int chunkSequence,

        @Schema(description = "Labor-time lines in canonical feed order")
        List<FeedLineDto> lines) {

    public FeedChunkDto {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
