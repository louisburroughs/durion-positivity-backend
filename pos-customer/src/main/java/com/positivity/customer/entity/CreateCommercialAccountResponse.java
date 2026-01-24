package com.positivity.customer.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response DTO for creating a commercial account.
 * Issue #176: Party: Create Commercial Account
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateCommercialAccountResponse {

    /**
     * Newly created party ID (canonical CRM identifier)
     */
    private String partyId;

    /**
     * Legal name of created party
     */
    private String legalName;

    /**
     * Status (ACTIVE|PENDING|SUSPENDED)
     */
    private String status;

    /**
     * Timestamp of creation
     */
    private Instant createdAt;

    /**
     * List of duplicate candidates found during creation.
     * If present, frontend should prompt user for confirmation.
     * Contains summary fields only (partyId, legalName, matchReason).
     */
    private List<DuplicateCandidate> duplicateCandidates;

    /**
     * Wrapper for duplicate candidates (returned on 409 or as advisory in 201)
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DuplicateCandidate {
        private String partyId;
        private String legalName;
        private String matchReason;
    }
}
