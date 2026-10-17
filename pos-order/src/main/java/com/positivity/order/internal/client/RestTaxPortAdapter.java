package com.positivity.order.internal.client;

import com.positivity.order.internal.exception.OrderTaxRefusedException;
import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
                    // #2644 (ADR-0017): pos-tax's configuration refusals are not "unreachable"; they become this
                    // module's deterministic 422 codes and are never swallowed into an empty answer.
                    .onStatus(status -> status.value() == 422, (req, res) -> {
                        String translated = OrderTaxRefusedException.translate(
                                errorCodeOf(new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
                        if (translated != null) {
                            throw new OrderTaxRefusedException(
                                    translated,
                                    "pos-tax refused the calculation for " + request.getCurrencyCode() + ": "
                                            + translated);
                        }
                    })
                    .body(TaxCalculationResponse.class);
            return Optional.ofNullable(response);
        } catch (OrderTaxRefusedException refused) {
            throw refused;
        } catch (Exception e) {
            log.warn("pos-tax unreachable: {}", e.getMessage());
            return Optional.empty();
        }
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
