package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for merging parties.
 * Issue #173: Party: Search and Merge Duplicate Parties
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Result of a party merge operation")
public class MergePartiesResponse {

    /**
     * Merge audit record ID (for traceability)
     */
    @Schema(
            description = "Merge audit record ID (for traceability)",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
    @NotBlank
    private String mergeAuditId;

    /**
     * Survivor party ID (canonical identifier for merged entity)
     */
    @Schema(
            description = "Survivor party ID (canonical identifier for merged entity)",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotBlank
    private String survivorPartyId;

    /**
     * Losing party ID (now merged away)
     */
    @Schema(
            description = "Losing party ID (now merged away)",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotBlank
    private String losingPartyId;

    /**
     * Alias/redirect ID for the losing party (for backwards compatibility).
     * When clients query the losing party ID, they should be redirected to
     * survivor.
     */
    @Schema(
            description = "Alias/redirect ID for the losing party, pointing to the survivor",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private String mergedPartyAlias;

    /**
     * Merge status (COMPLETED|PENDING)
     */
    @Schema(description = "Merge status", example = "COMPLETED", requiredMode = REQUIRED)
    @NotBlank
    private String status;

    /**
     * Timestamp of merge completion (ISO 8601)
     */
    @Schema(
            description = "Timestamp of merge completion (ISO 8601)",
            example = "2026-02-20T14:45:00Z",
            requiredMode = NOT_REQUIRED)
    private String completedAt;
}
