package com.positivity.vehicle.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Outcome of one page of a vehicle-fact replay. */
@Schema(description = "Outcome of one page of a vehicle-fact replay.")
public record VehicleFactReplayResultDto(
        @Schema(description = "Facts published by this call.", example = "500")
        int emitted,

        @Schema(
                description = "Cursor for the next page; null when the last vehicle was reached.",
                example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b")
        @Nullable
        UUID nextAfterId,

        @Schema(description = "True when no further pages remain for this filter.", example = "false")
        boolean complete,

        @Schema(description = "The updatedSince filter this page ran under, echoed back.") @Nullable
        Instant updatedSince,

        @Schema(description = "When this page began.") @NonNull
        Instant startedAt) {}
