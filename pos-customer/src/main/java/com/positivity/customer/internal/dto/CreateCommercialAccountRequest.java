package com.positivity.customer.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for creating a commercial account (party).
 * Issue #176: Party: Create Commercial Account
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateCommercialAccountRequest {

    /**
     * Legal business name (required)
     */
    private String legalName;

    /**
     * Display/trading name (optional)
     */
    private String displayName;

    /**
     * Tax identification number (optional, but required for certain jurisdictions)
     */
    private String taxId;

    /**
     * Party type (ORGANIZATION|INDIVIDUAL; default ORGANIZATION for commercial
     * accounts)
     */
    private String partyType;

    /**
     * Billing terms ID (foreign key to Billing domain)
     */
    private String billingTermsId;

    /**
     * External identifiers (system-specific IDs from upstream systems).
     * Format TBD: fixed set vs key/value map.
     */
    private Map<String, String> externalIdentifiers;

    /**
     * Primary contact first name (optional)
     */
    private String contactFirstName;

    /**
     * Primary contact last name (optional)
     */
    private String contactLastName;

    /**
     * Contact email (optional)
     */
    private String email;

    /**
     * Contact phone (optional)
     */
    private String phone;
}
