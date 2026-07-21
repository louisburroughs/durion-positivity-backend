package com.positivity.tax.internal.service;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.service.TaxCalculationService;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/**
 * Implementation of TaxCalculationService.
 * <p>
 * Routes tax calculation requests to either test mode calculator or external service
 * based on configuration.
 */
@Slf4j
@Service
public class TaxCalculationServiceImpl implements TaxCalculationService {

    private final TaxProperties properties;
    private final TaxProviderSelector providerSelector;

    public TaxCalculationServiceImpl(TaxProperties properties, TaxProviderSelector providerSelector) {
        this.properties = properties;
        this.providerSelector = providerSelector;
    }

    @Override
    @NonNull
    public TaxCalculationResponse calculateTax(@NonNull TaxCalculationRequest request) {
        validateRequest(request);

        // Provider abstraction (story T6): select the test-mode or external provider and
        // delegate the uncommitted estimate. Commit/void document lifecycle is driven
        // separately by TaxProviderLifecycleService at invoice finalization/revert.
        return providerSelector.select().estimate(request);
    }

    @Override
    public boolean isTestMode() {
        return properties.getTestMode().isEnabled();
    }

    /**
     * Validate the tax calculation request.
     *
     * @param request the request to validate
     * @throws IllegalArgumentException if the request is invalid
     */
    private void validateRequest(@NonNull TaxCalculationRequest request) {
        if (request.getLineItems() == null || request.getLineItems().isEmpty()) {
            throw new IllegalArgumentException("At least one line item is required");
        }

        if (request.getPostalCode() == null || request.getPostalCode().isBlank()) {
            throw new IllegalArgumentException("Postal code is required");
        }

        // Validate each line item has necessary data
        for (int i = 0; i < request.getLineItems().size(); i++) {
            var item = request.getLineItems().get(i);

            if (item.getLineItemId() == null || item.getLineItemId().isBlank()) {
                throw new IllegalArgumentException("Line item at index " + i + " is missing lineItemId");
            }

            if (item.getQuantity() == null || item.getQuantity().signum() <= 0) {
                throw new IllegalArgumentException("Line item " + item.getLineItemId() + " has invalid quantity");
            }

            if (item.getUnitPrice() == null || item.getUnitPrice().signum() < 0) {
                throw new IllegalArgumentException("Line item " + item.getLineItemId() + " has invalid unit price");
            }
        }
    }
}
