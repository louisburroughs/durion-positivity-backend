package com.positivity.workorder.service;

import java.util.Optional;

import com.positivity.workorder.internal.dto.BillingRulesDTO;

public interface BillingRulesClientService {

    /**
     * Fetch billing rules for a customer/party.
     * Returns empty if rules don't exist or service call fails.
     *
     * @param partyId the party/customer identifier
     * @return billing rules if found
     */
    Optional<BillingRulesDTO> getBillingRules(String partyId);

    /**
     * Check if a customer requires purchase order for approvals.
     * Returns false if rules don't exist or service call fails (fail-safe).
     *
     * @param partyId the party/customer identifier
     * @return true if PO is required, false otherwise
     */
    boolean isPurchaseOrderRequired(String partyId);

}