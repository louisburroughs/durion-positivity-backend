package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(
        description = "Request to reprocess a suspended accounting event. The triggering user is the authenticated"
                + " caller and is never taken from the body.")
public class ReprocessEventRequest {

    /**
     * Optional: specific mapping version to use for reprocessing.
     * If not provided, uses current (latest) mapping rules.
     */
    @Schema(
            description = "Specific mapping version (UUID) to use; defaults to latest when omitted",
            example = "0198a1b2-7c3d-7e4f-8a9b-0c1d2e3f4a5c",
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
