package com.positivity.workorder.internal.client;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.error.TaxRefusals;
import com.positivity.workorder.internal.exception.EstimateTaxRefusedException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
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
                // pos-tax is internal-only and guards /v1/tax/calculate with
                // @PreAuthorize("hasAuthority('" + WorkorderPermissions.TAX_CALCULATE + "')"). Propagate the required
                // authority via the
                // gateway authorities header for this service-to-service call (see
                // GatewayAuthoritiesFilter and the pos-invoice TaxServiceClient pattern).
                .header("X-User", "pos-workorder")
                .header("X-Authorities", "tax:calculate")
                .body(request)
                .retrieve()
                // #2644 (ADR-0017): pos-tax's configuration refusals become this module's deterministic codes.
                .onStatus(status -> status.value() == 422, (req, res) -> {
                    byte[] body = res.getBody().readAllBytes();
                    String translated = EstimateTaxRefusedException.translate(TaxRefusals.codeOf(body));
                    if (translated == null) {
                        // Any other 422 keeps the client's previous behaviour: the status exception with its body.
                        throw HttpClientErrorException.create(
                                res.getStatusCode(),
                                res.getStatusText(),
                                res.getHeaders(),
                                body,
                                StandardCharsets.UTF_8);
                    }
                    throw new EstimateTaxRefusedException(
                            translated,
                            "pos-tax refused the calculation for " + request.getCurrencyCode() + ": " + translated);
                })
                .body(TaxCalculationResponse.class);

        if (response == null) {
            throw new IllegalStateException("Tax service returned an empty response for tax calculation");
        }

        return response;
    }
}
