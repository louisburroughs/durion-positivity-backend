package com.positivity.tax.internal.service;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.internal.exception.TaxCalculationException;

import io.github.resilience4j.retry.Retry;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.function.Supplier;

/**
 * Client for external tax service API.
 * <p>
 * This component handles communication with the external tax calculation
 * service,
 * including retry logic, error handling, and response mapping.
 */
@Slf4j
@Component
public class ExternalTaxServiceClient {

    private final RestClient restClient;
    private final Retry retry;

    public ExternalTaxServiceClient(RestClient taxServiceRestClient, Retry taxServiceRetry) {
        this.restClient = taxServiceRestClient;
        this.retry = taxServiceRetry;
    }

    /**
     * Calculate tax using the external tax service API.
     *
     * @param request the tax calculation request
     * @return the calculated tax response from external service
     * @throws RuntimeException if the external service call fails after retries
     */
    @NonNull
    public TaxCalculationResponse calculateTax(@NonNull TaxCalculationRequest request) {
        log.info("Calling external tax service for postal code: {}", request.getPostalCode());

        Supplier<TaxCalculationResponse> supplier = () -> {
            try {
                TaxCalculationResponse response = restClient.post()
                        .uri("/v1/calculate")
                        .body(request)
                        .retrieve()
                        .body(TaxCalculationResponse.class);

                if (response == null) {
                    throw new TaxCalculationException("External tax service returned null response");
                }

                // Mark response as not from test mode
                response.setTestMode(false);

                log.info("Successfully received tax calculation from external service. Total tax: {}",
                        response.getTotalTax());

                return response;

            } catch (Exception e) {
                log.error("Error calling external tax service: {}", e.getMessage(), e);
                throw new TaxCalculationException("Failed to calculate tax from external service", e);
            }
        };

        // Apply retry logic
        Supplier<TaxCalculationResponse> decoratedSupplier = Retry.decorateSupplier(retry, supplier);

        return decoratedSupplier.get();
    }
}
