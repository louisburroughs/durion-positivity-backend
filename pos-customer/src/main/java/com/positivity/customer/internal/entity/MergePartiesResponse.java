package com.positivity.customer.internal.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
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
public class MergePartiesResponse {

    /**
     * Merge audit record ID (for traceability)
     */
    private String mergeAuditId;

    /**
     * Survivor party ID (canonical identifier for merged entity)
     */
    private String survivorPartyId;

    /**
     * Losing party ID (now merged away)
     */
    private String losingPartyId;

    /**
     * Alias/redirect ID for the losing party (for backwards compatibility).
     * When clients query the losing party ID, they should be redirected to
     * survivor.
     */
    private String mergedPartyAlias;

    /**
     * Merge status (COMPLETED|PENDING)
     */
    private String status;

    /**
     * Timestamp of merge completion (ISO 8601)
     */
    private String completedAt;
}
