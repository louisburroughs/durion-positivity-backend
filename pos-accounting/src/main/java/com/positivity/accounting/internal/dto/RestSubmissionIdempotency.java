package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The idempotency mechanism used by {@code POST /v1/accounting/events} (issue #2207):
 * content-hash dedup via {@code IdempotencyService}, a 24-hour window, and a hard rejection —
 * nothing is persisted and {@code AccountingEventResponse.idempotencyOutcome} stays {@code null}
 * for both the original and the rejected replay.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Idempotency mechanism for events submitted via POST /v1/accounting/events")
public class RestSubmissionIdempotency {

    @Schema(description = "Dedup mechanism", example = "CONTENT_HASH", requiredMode = REQUIRED)
    private String mechanism;

    @Schema(description = "Deduplication window", example = "24h", requiredMode = REQUIRED)
    private String window;

    @Schema(description = "HTTP status returned for a detected replay", example = "409", requiredMode = REQUIRED)
    private int onDuplicateHttpStatus;

    @Schema(
            description = "ApiError code returned for a detected replay",
            example = "DUPLICATE_EVENT",
            requiredMode = REQUIRED)
    private String onDuplicateErrorCode;

    @Schema(
            description = "What happens on a detected replay: nothing is persisted",
            example =
                    "A replay persists nothing; idempotencyOutcome is not applicable to this path " + "and stays null.",
            requiredMode = REQUIRED)
    private String onDuplicateBehavior;
}
