package com.positivity.customer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for retrieving a party by partyId.
 * Issue #176: Party: Create Commercial Account
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GetPartyResponse {

    /**
     * Party ID (canonical CRM identifier)
     */
    private String partyId;

    /**
     * Party type (ORGANIZATION|INDIVIDUAL)
     */
    private String partyType;

    /**
     * Legal name
     */
    private String legalName;

    /**
     * Display/trading name
     */
    private String displayName;

    /**
     * Tax identification number
     */
    private String taxId;

    /**
     * Current status (ACTIVE|PENDING|SUSPENDED|INACTIVE)
     */
    private String status;

    /**
     * Billing terms ID
     */
    private String billingTermsId;

    /**
     * Party creation timestamp (ISO 8601)
     */
    private String createdAt;

    /**
     * Last modification timestamp (ISO 8601)
     */
    private String modifiedAt;
}
