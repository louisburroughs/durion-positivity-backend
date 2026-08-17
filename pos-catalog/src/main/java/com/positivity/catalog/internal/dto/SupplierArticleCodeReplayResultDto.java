package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Result of one bounded vendor-article-code replay page (CAP-320 #1347, following #1309).
 *
 * @param emitted      facts published by this call
 * @param nextAfterId  cursor to pass as {@code afterId} on the next call; null when the replay
 *                     reached the end of the table
 * @param complete     true when no further pages remain for this filter
 * @param updatedSince the filter this page ran under, echoed so a paging script cannot drift
 * @param startedAt    when this page began
 */
@Schema(description = "Outcome of one page of a vendor-article-code fact replay.")
public record SupplierArticleCodeReplayResultDto(
        @Schema(description = "Facts published by this call.", example = "500")
        int emitted,

        @Schema(
                description = "Cursor for the next page; null when the table end was reached.",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b")
        @Nullable
        UUID nextAfterId,

        @Schema(description = "True when no further pages remain for this filter.", example = "false")
        boolean complete,

        @Schema(description = "The updatedSince filter this page ran under, echoed back.") @Nullable
        Instant updatedSince,

        @Schema(description = "When this page began.") @NonNull
        Instant startedAt) {}
