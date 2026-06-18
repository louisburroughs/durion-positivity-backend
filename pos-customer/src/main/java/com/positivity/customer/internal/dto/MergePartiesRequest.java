package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for merging duplicate parties.
 * Issue #173: Party: Search and Merge Duplicate Parties
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Request to merge a duplicate (losing) party into a survivor party")
public class MergePartiesRequest {

    /**
     * ID of the party to merge into (survivor party).
     * Extracted from URL path: /parties/{partyId}/merge
     */
    @Schema(
            description = "ID of the party to merge into (survivor party). Sourced from the URL path.",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private String survivorPartyId;

    /**
     * ID of the party to be merged away (losing party).
     * Must be provided in request body.
     */
    @Schema(
            description = "ID of the party to be merged away (losing party)",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotBlank
    private String losingPartyId;

    /**
     * Justification for merge (required).
     * Min/max length TBD.
     */
    @Schema(
            description = "Justification for the merge",
            example = "Duplicate party created during onboarding; consolidating records.",
            requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 1000)
    private String justification;
}
