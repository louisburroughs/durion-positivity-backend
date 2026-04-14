package com.positivity.customer.service;

import com.positivity.customer.internal.dto.GetAccountTierResponse;
import com.positivity.customer.internal.dto.ResolveAccountTierRequest;
import com.positivity.customer.internal.dto.ResolveAccountTierResponse;
import java.util.UUID;

public interface AccountTierService {

    /**
     * Get the current tier for an account.
     *
     * @param accountId the account/party identifier
     * @return tier information response
     * @throws IllegalArgumentException if account not found
     */
    GetAccountTierResponse getAccountTier(UUID accountId);

    /**
     * Resolve/compute the appropriate tier for an account based on business rules.
     *
     * Optionally applies the resolved tier to the account if requested.
     *
     * @param request tier resolution request with calculation criteria
     * @return resolution result with recommended tier
     * @throws IllegalArgumentException if account not found
     */
    ResolveAccountTierResponse resolveAccountTier(ResolveAccountTierRequest request);
}
