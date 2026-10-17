package com.positivity.workorder.internal.client;

import com.positivity.workorder.internal.exception.EstimateTaxRefusedException;
import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
                    String translated = EstimateTaxRefusedException.translate(
                            errorCodeOf(new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
                    if (translated != null) {
                        throw new EstimateTaxRefusedException(
                                translated,
                                "pos-tax refused the calculation for " + request.getCurrencyCode() + ": " + translated);
                    }
                })
                .body(TaxCalculationResponse.class);

        if (response == null) {
            throw new IllegalStateException("Tax service returned an empty response for tax calculation");
        }

        return response;
    }

    /** pos-tax's 422 codes a caller must answer deterministically (#2644, ADR-0017). */
    private static final java.util.regex.Pattern ERROR_CODE =
            java.util.regex.Pattern.compile("\"code\"\\s*:\\s*\"([A-Z_]+)\"");

    static @Nullable String errorCodeOf(@Nullable String body) {
        if (body == null) {
            return null;
        }
        java.util.regex.Matcher matcher = ERROR_CODE.matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

}
