package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for searching parties.
 * Issue #173: Party: Search and Merge Duplicate Parties
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Paged response of matching party summaries")
public class SearchPartiesResponse {

    /**
     * List of party summary records
     */
    @Schema(description = "List of party summary records", requiredMode = REQUIRED)
    @NotNull
    @Valid
    private List<PartySummary> results;

    /**
     * Total number of matching records
     */
    @Schema(description = "Total number of matching records", example = "42", requiredMode = REQUIRED)
    @NotNull
    private Integer totalCount;

    /**
     * Current page number
     */
    @Schema(description = "Current page number", example = "1", requiredMode = REQUIRED)
    @NotNull
    private Integer pageNumber;

    /**
     * Items per page
     */
    @Schema(description = "Items per page", example = "20", requiredMode = REQUIRED)
    @NotNull
    private Integer pageSize;

    /**
     * Party summary for search results
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Party summary record for a directory row")
    public static class PartySummary {

        @Schema(
                description = "Unique identifier of the party",
                example = "01960003-0000-7000-8000-000000000001",
                requiredMode = REQUIRED)
        @NotNull
        private String partyId;

        @Schema(description = "Legal name of the party", example = "Acme Corporation", requiredMode = NOT_REQUIRED)
        private String legalName;

        @Schema(description = "Display name of the party", example = "Acme", requiredMode = NOT_REQUIRED)
        private String displayName;

        @Schema(description = "Party type (ORGANIZATION|INDIVIDUAL)", example = "ORGANIZATION", requiredMode = NOT_REQUIRED)
        private String partyType;

        @Schema(description = "Party status (ACTIVE|PENDING|SUSPENDED|INACTIVE)", example = "ACTIVE", requiredMode = NOT_REQUIRED)
        private String status;

        @Schema(description = "Creation timestamp (ISO 8601)", example = "2026-01-15T09:30:00Z", requiredMode = NOT_REQUIRED)
        private String createdAt;

        /**
         * Primary contact for the party, surfaced so the customer directory can render
         * it without a per-row fetch. Null when the party has no resolvable contact.
         */
        @Schema(
                description = "Primary contact for the party; null when none is resolvable",
                requiredMode = NOT_REQUIRED)
        @Valid
        private PrimaryContact primaryContact;

        /**
         * Number of vehicles associated with the party, surfaced so the directory can
         * show a count without a per-row fetch. Defaults to 0.
         */
        @Schema(
                description = "Number of vehicles associated with the party (defaults to 0)",
                example = "3",
                requiredMode = NOT_REQUIRED)
        private Integer vehicleCount;
    }

    /**
     * Lightweight primary-contact projection for directory rows.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Lightweight primary-contact projection for directory rows")
    public static class PrimaryContact {

        @Schema(description = "Contact name", example = "Jane Doe", requiredMode = NOT_REQUIRED)
        private String name;

        @Schema(description = "Contact email address", example = "jane@acme.com", requiredMode = NOT_REQUIRED)
        private String email;

        @Schema(description = "Contact phone number", example = "+1-555-0100", requiredMode = NOT_REQUIRED)
        private String phone;
    }
}
