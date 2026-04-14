package com.positivity.workorder.internal.client;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * REST client for communication with pos-tax service.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TaxClient {

    private final RestClient taxServiceRestClient;

    @NonNull
    public TaxCalculationResponse calculateTax(@NonNull TaxCalculationRequest request) {
        TaxCalculationResponse response = taxServiceRestClient
                .post()
                .uri("/v1/tax/calculate")
                .body(request)
                .retrieve()
                .body(TaxCalculationResponse.class);

        if (response == null) {
            throw new IllegalStateException("Tax service returned an empty response for tax calculation");
        }

        return response;
    }
}
