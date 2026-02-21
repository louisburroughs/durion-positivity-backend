package com.positivity.tax.service;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import org.jspecify.annotations.NonNull;

/**
 * Public API for tax calculation service.
 * <p>
 * This is the only service interface exposed to other modules.
 * Use this interface for programmatic tax calculations from other services.
 * <p>
 * The implementation automatically routes to either:
 * <ul>
 *   <li>External tax service API (when test mode is disabled)</li>
 *   <li>Internal test calculator (when test mode is enabled)</li>
 * </ul>
 */
public interface TaxCalculationService {

    /**
     * Calculate tax for the provided request.
     * <p>
     * In production mode, this delegates to an external tax service API.
     * In test mode, this uses a simple configurable calculator.
     *
     * @param request the tax calculation request containing line items and location
     * @return the calculated tax response with breakdown by jurisdiction
     * @throws IllegalArgumentException if the request is invalid
     */
    @NonNull
    TaxCalculationResponse calculateTax(@NonNull TaxCalculationRequest request);

    /**
     * Check if the service is currently in test mode.
     *
     * @return true if test mode is enabled, false otherwise
     */
    boolean isTestMode();
}
