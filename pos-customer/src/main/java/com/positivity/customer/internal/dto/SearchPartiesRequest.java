package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for searching parties.
 * Issue #173: Party: Search and Merge Duplicate Parties
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Request to search parties with optional filters and pagination")
public class SearchPartiesRequest {

    /**
     * Party name search term (matches legalName or displayName).
     * Search type (exact|prefix|contains) TBD.
     */
    @Schema(
            description = "Party name search term (matches legalName or displayName)",
            example = "Acme Corporation",
            requiredMode = NOT_REQUIRED)
    @Size(max = 200)
    private String name;

    /**
     * Search by email address
     */
    @Schema(description = "Search by email address", example = "jane@acme.com", requiredMode = NOT_REQUIRED)
    @Size(max = 254)
    private String email;

    /**
     * Search by phone number
     */
    @Schema(description = "Search by phone number", example = "+1-555-0100", requiredMode = NOT_REQUIRED)
    @Size(max = 50)
    private String phone;

    /**
     * Search by tax ID
     */
    @Schema(description = "Search by tax ID", example = "12-3456789", requiredMode = NOT_REQUIRED)
    @Size(max = 50)
    private String taxId;

    /**
     * Filter by party type (ORGANIZATION|INDIVIDUAL)
     */
    @Schema(
            description = "Filter by party type (ORGANIZATION|INDIVIDUAL)",
            example = "ORGANIZATION",
            requiredMode = NOT_REQUIRED)
    @Size(max = 40)
    private String partyType;

    /**
     * Filter by status (ACTIVE|PENDING|SUSPENDED|INACTIVE)
     */
    @Schema(
            description = "Filter by status (ACTIVE|PENDING|SUSPENDED|INACTIVE)",
            example = "ACTIVE",
            requiredMode = NOT_REQUIRED)
    @Size(max = 40)
    private String status;

    /**
     * Include merged parties in results (default false).
     * Admin-only toggle for troubleshooting.
     */
    @Schema(
            description = "Include merged parties in results (default false); admin-only troubleshooting toggle",
            example = "false",
            requiredMode = NOT_REQUIRED)
    private Boolean includeMerged;

    /**
     * Pagination: page number (1-indexed, default 1)
     */
    @Schema(description = "Pagination: page number (1-indexed, default 1)", example = "1", requiredMode = NOT_REQUIRED)
    @Min(1)
    private Integer pageNumber;

    /**
     * Pagination: items per page (default 20, max 100)
     */
    @Schema(
            description = "Pagination: items per page (default 20, max 100)",
            example = "20",
            requiredMode = NOT_REQUIRED)
    @Min(1)
    @Max(100)
    private Integer pageSize;

    /**
     * Sort field (legalName|createdAt|modifiedAt)
     */
    @Schema(
            description = "Sort field (legalName|createdAt|modifiedAt)",
            example = "legalName",
            requiredMode = NOT_REQUIRED)
    @Size(max = 40)
    private String sortField;

    /**
     * Sort order (ASC|DESC)
     */
    @Schema(description = "Sort order (ASC|DESC)", example = "ASC", requiredMode = NOT_REQUIRED)
    @Size(max = 4)
    private String sortOrder;
}
