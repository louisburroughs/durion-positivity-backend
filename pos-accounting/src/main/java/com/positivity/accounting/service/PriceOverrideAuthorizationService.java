package com.positivity.accounting.service;

import com.positivity.accounting.internal.service.PriceOverrideAuthorizationServiceImpl.AuthorizationResult;
import java.math.BigDecimal;

public interface PriceOverrideAuthorizationService {

    /**
     * Validates if a price override is authorized for the given role.
     *
     * @param role          the actor's role
     * @param originalPrice the original price
     * @param adjustedPrice the adjusted price
     * @param categoryCode  optional category code to check against forbidden list
     * @return validation result
     */
    AuthorizationResult validate(String role, BigDecimal originalPrice, BigDecimal adjustedPrice, String categoryCode);
}
