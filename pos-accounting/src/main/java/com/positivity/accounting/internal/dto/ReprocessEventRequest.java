package com.positivity.accounting.internal.dto;

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
public class ReprocessEventRequest {

    /**
     * User ID triggering the reprocessing.
     * Required for audit trail.
     */
    @NotBlank(message = "triggeredByUserId is required")
    private String triggeredByUserId;

    /**
     * Optional: specific mapping version to use for reprocessing.
     * If not provided, uses current (latest) mapping rules.
     */
    private String mappingVersionToUse;

    /**
     * Optional: notes or context about why reprocessing is being triggered.
     */
    private String reprocessingNotes;
}
