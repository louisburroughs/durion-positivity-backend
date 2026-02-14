package com.positivity.tax.internal.service;

import com.positivity.tax.internal.config.TaxProperties;
import com.positivity.tax.internal.dto.TaxCalculationRequest;
import com.positivity.tax.internal.dto.TaxCalculationResponse;
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
    private final TestModeTaxCalculator testCalculator;
    private final ExternalTaxServiceClient externalClient;

    public TaxCalculationServiceImpl(
        TaxProperties properties,
        TestModeTaxCalculator testCalculator,
        ExternalTaxServiceClient externalClient
    ) {
        this.properties = properties;
        this.testCalculator = testCalculator;
        this.externalClient = externalClient;
    }

    @Override
    @NonNull
    public TaxCalculationResponse calculateTax(@NonNull TaxCalculationRequest request) {
        validateRequest(request);

        if (isTestMode()) {
            log.debug("Using test mode tax calculator");
            return testCalculator.calculate(request);
        } else {
            log.debug("Using external tax service");
            return externalClient.calculateTax(request);
        }
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
                throw new IllegalArgumentException(
                    "Line item at index " + i + " is missing lineItemId"
                );
            }
            
            if (item.getQuantity() == null || item.getQuantity().signum() <= 0) {
                throw new IllegalArgumentException(
                    "Line item " + item.getLineItemId() + " has invalid quantity"
                );
            }
            
            if (item.getUnitPrice() == null || item.getUnitPrice().signum() < 0) {
                throw new IllegalArgumentException(
                    "Line item " + item.getLineItemId() + " has invalid unit price"
                );
            }
        }
    }
}
