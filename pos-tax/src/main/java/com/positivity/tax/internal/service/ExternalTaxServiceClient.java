package com.positivity.tax.internal.service;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.internal.exception.TaxCalculationException;
import io.github.resilience4j.retry.Retry;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
                TaxCalculationResponse response = restClient
                        .post()
                        .uri("/v1/calculate")
                        .body(request)
                        .retrieve()
                        .body(TaxCalculationResponse.class);

                if (response == null) {
                    throw new TaxCalculationException("External tax service returned null response");
                }

                // Mark response as not from test mode
                response.setTestMode(false);

                log.info(
                        "Successfully received tax calculation from external service. Total tax: {}",
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

    /**
     * Commit the provider tax document identified by {@code referenceId}.
     * <p>
     * <strong>External adapter pending provider selection (R-T1)</strong>: this is a
     * structured stub over the existing generic HTTP shape — it posts the reference as the
     * provider document code (idempotency key) and returns the provider transaction id from
     * the response. The concrete provider API (Avalara/Stripe/TaxJar) is wired in Wave 5.
     *
     * @param referenceId the document code / idempotency key
     * @return the provider transaction id, or {@code null} if the provider returned none
     */
    @Nullable
    public String commitDocument(@NonNull UUID referenceId) {
        return callLifecycle("commit", referenceId);
    }

    /**
     * Void the provider tax document identified by {@code referenceId}.
     * <p>
     * <strong>External adapter pending provider selection (R-T1)</strong> — see
     * {@link #commitDocument(UUID)}.
     *
     * @param referenceId the document code / idempotency key
     * @return the provider transaction id, or {@code null} if the provider returned none
     */
    @Nullable
    public String voidDocument(@NonNull UUID referenceId) {
        return callLifecycle("void", referenceId);
    }

    @Nullable
    private String callLifecycle(@NonNull String action, @NonNull UUID referenceId) {
        Supplier<String> supplier = () -> {
            try {
                return restClient
                        .post()
                        .uri("/v1/documents/{referenceId}/{action}", referenceId, action)
                        .retrieve()
                        .body(String.class);
            } catch (Exception e) {
                log.error("Error calling external tax service {} for {}: {}", action, referenceId, e.getMessage(), e);
                throw new TaxCalculationException("Failed to " + action + " tax document at external service", e);
            }
        };
        return Retry.decorateSupplier(retry, supplier).get();
    }
}
