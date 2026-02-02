package com.positivity.customer.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
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
public class SearchPartiesRequest {

    /**
     * Party name search term (matches legalName or displayName).
     * Search type (exact|prefix|contains) TBD.
     */
    private String name;

    /**
     * Search by email address
     */
    private String email;

    /**
     * Search by phone number
     */
    private String phone;

    /**
     * Search by tax ID
     */
    private String taxId;

    /**
     * Filter by party type (ORGANIZATION|INDIVIDUAL)
     */
    private String partyType;

    /**
     * Filter by status (ACTIVE|PENDING|SUSPENDED|INACTIVE)
     */
    private String status;

    /**
     * Include merged parties in results (default false).
     * Admin-only toggle for troubleshooting.
     */
    private Boolean includeMerged;

    /**
     * Pagination: page number (1-indexed, default 1)
     */
    private Integer pageNumber;

    /**
     * Pagination: items per page (default 20, max 100)
     */
    private Integer pageSize;

    /**
     * Sort field (legalName|createdAt|modifiedAt)
     */
    private String sortField;

    /**
     * Sort order (ASC|DESC)
     */
    private String sortOrder;
}
