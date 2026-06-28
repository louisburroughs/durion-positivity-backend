package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.ReprocessingOutcome;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for reprocessing attempt history response.
 *
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Reprocessing History</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Historical record of a single reprocessing attempt")
public class ReprocessingAttemptHistoryResponse {

    @Schema(
            description = "Unique identifier of the reprocessing attempt",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID attemptId;

    @Schema(
            description = "Identifier of the event that was reprocessed",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID eventId;

    @Schema(
            description = "Timestamp when the reprocessing was attempted (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant attemptedAt;

    @Schema(
            description = "Identifier of the user who triggered the attempt",
            example = "ap.manager",
            requiredMode = NOT_REQUIRED)
    private String triggeredByUserId;

    @Schema(description = "Outcome of the reprocessing attempt", example = "SUCCESS", requiredMode = NOT_REQUIRED)
    private ReprocessingOutcome outcome;

    @Schema(
            description = "Additional detail about the outcome",
            example = "Posted to GL successfully",
            requiredMode = NOT_REQUIRED)
    private String outcomeDetails;

    @Schema(description = "Mapping version used for the attempt", example = "v3", requiredMode = NOT_REQUIRED)
    private String mappingVersionUsed;
}
