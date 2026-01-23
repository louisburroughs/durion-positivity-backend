package com.positivity.customer.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

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
     * Contact email (optional)
     */
    private String email;

    /**
     * Contact phone (optional)
     */
    private String phone;
}
