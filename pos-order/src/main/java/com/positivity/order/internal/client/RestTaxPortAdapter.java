package com.positivity.order.internal.client;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * REST adapter on pos-tax {@code /v1/tax/calculate} (parity story B3), following pos-workorder's
 * {@code TaxClient}: service-to-service authority via the gateway headers.
 */
@Component
@Slf4j
public class RestTaxPortAdapter implements TaxPort {

    private final RestClient taxServiceRestClient;

    public RestTaxPortAdapter(@Qualifier("taxServiceRestClient") RestClient taxServiceRestClient) {
        this.taxServiceRestClient = taxServiceRestClient;
    }

    @Override
    public @NonNull Optional<TaxCalculationResponse> calculate(@NonNull TaxCalculationRequest request) {
        try {
            TaxCalculationResponse response = taxServiceRestClient
                    .post()
                    .uri("/v1/tax/calculate")
                    .header("X-User", "pos-order")
                    .header("X-Authorities", "tax:calculate")
                    .body(request)
                    .retrieve()
                    .body(TaxCalculationResponse.class);
            return Optional.ofNullable(response);
        } catch (Exception e) {
            log.warn("pos-tax unreachable: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
