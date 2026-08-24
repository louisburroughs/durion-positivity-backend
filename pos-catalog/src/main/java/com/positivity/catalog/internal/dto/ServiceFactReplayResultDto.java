package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Result of one bounded service-fact replay page (#1306, ADR-0044 §4).
 *
 * <p>Shaped like {@link ProductFactReplayResultDto} on purpose: an operator seeding a consumer's
 * catalog replica runs both replays back to back, and two result shapes for the same operation
 * would mean two paging scripts.
 *
 * @param emitted      facts published by this call
 * @param nextAfterId  cursor to pass as {@code afterServiceId} on the next call; null when the
 *                     replay reached the end of the service catalog
 * @param complete     true when no further pages remain for this filter
 * @param updatedSince the filter this page ran under, echoed so a paging script cannot drift
 * @param startedAt    when this page began
 */
@Schema(description = "Outcome of one page of a service-fact replay.")
public record ServiceFactReplayResultDto(
        @Schema(description = "Facts published by this call.", example = "120")
        int emitted,

        @Schema(
                description = "Cursor for the next page; null when the end of the service catalog was reached.",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b")
        @Nullable
        UUID nextAfterId,

        @Schema(description = "True when no further pages remain for this filter.", example = "false")
        boolean complete,

        @Schema(description = "The updatedSince filter this page ran under, echoed back.") @Nullable
        Instant updatedSince,

        @Schema(description = "When this page began.") @NonNull
        Instant startedAt) {}
