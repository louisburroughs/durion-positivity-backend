package com.positivity.invoice.service;

import com.positivity.invoice.internal.dto.BillingRulesDTO;
import org.jspecify.annotations.NonNull;

import java.util.Optional;

/**
 * Service for managing billing rules.
 * Public API for cross-module access.
 * CAP:092 - Preferences & Billing Rules
 */
public interface BillingRulesService {

    /**
     * Retrieve billing rules for a party/customer.
     *
     * @param partyId the party identifier
     * @return the billing rules if found
     */
    @NonNull
    Optional<BillingRulesDTO> getBillingRules(@NonNull String partyId);

    /**
     * Idempotent upsert of billing rules for a party/customer.
     * Creates if not exists, updates if exists.
     *
     * @param billingRules the billing rules to save
     * @param updatedBy    the user/service performing the update
     * @return the saved billing rules
     */
    @NonNull
    BillingRulesDTO saveBillingRules(@NonNull BillingRulesDTO billingRules, @NonNull String updatedBy);

    /**
     * Create default billing rules for a new commercial account.
     * Idempotent - safe to call multiple times for the same partyId.
     *
     * @param partyId the party identifier
     * @return the created billing rules
     */
    @NonNull
    BillingRulesDTO createDefaultBillingRules(@NonNull String partyId);

    /**
     * Get the current authenticated user ID from the security context.
     *
     * @return the current user ID or a default value if not authenticated
     */
    @NonNull
    String getCurrentUsername();
}
