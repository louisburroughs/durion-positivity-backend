package com.positivity.customer.internal.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
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
public class MergePartiesRequest {

    /**
     * ID of the party to merge into (survivor party).
     * Extracted from URL path: /parties/{partyId}/merge
     */
    private String survivorPartyId;

    /**
     * ID of the party to be merged away (losing party).
     * Must be provided in request body.
     */
    private String losingPartyId;

    /**
     * Justification for merge (required).
     * Min/max length TBD.
     */
    private String justification;
}
