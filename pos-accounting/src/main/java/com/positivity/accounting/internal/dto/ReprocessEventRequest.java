package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for reprocessing a suspended accounting event.
 *
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - Reprocess Event</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to reprocess a suspended accounting event")
public class ReprocessEventRequest {

    /**
     * User ID triggering the reprocessing.
     * Required for audit trail.
     */
    @Schema(
            description = "Identifier of the user triggering the reprocessing (required for audit trail)",
            example = "ap.manager",
            requiredMode = REQUIRED)
    @NotBlank(message = "triggeredByUserId is required")
    private String triggeredByUserId;

    /**
     * Optional: specific mapping version to use for reprocessing.
     * If not provided, uses current (latest) mapping rules.
     */
    @Schema(
            description = "Specific mapping version to use; defaults to latest when omitted",
            example = "v3",
            requiredMode = NOT_REQUIRED)
    private String mappingVersionToUse;

    /**
     * Optional: notes or context about why reprocessing is being triggered.
     */
    @Schema(
            description = "Optional notes about why reprocessing is being triggered",
            example = "Mapping rules corrected after vendor GL update",
            requiredMode = NOT_REQUIRED)
    private String reprocessingNotes;
}
