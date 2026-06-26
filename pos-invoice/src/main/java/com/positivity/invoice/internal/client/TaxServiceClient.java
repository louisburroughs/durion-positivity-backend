package com.positivity.invoice.internal.client;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class TaxServiceClient {

    private static final Logger log = LoggerFactory.getLogger(TaxServiceClient.class);

    private final RestClient restClient;

    public TaxServiceClient(
            RestClient.Builder restClientBuilder,
            @Value("${invoice.tax.base-url:http://pos-tax:8091/v1/tax}") String taxServiceBaseUrl) {
        this.restClient = restClientBuilder.baseUrl(taxServiceBaseUrl).build();
    }

    @NonNull
    public BigDecimal calculateTax(@NonNull BigDecimal subtotal, String partyId) {
        if (log.isDebugEnabled()) {
            log.debug("Calculating tax for subtotal {} and partyId(mask) {}", subtotal, maskForLog(partyId));
        }

        TaxCalculationResponse response = restClient
                .post()
                .uri("/calculate")
                .contentType(MediaType.APPLICATION_JSON)
                // pos-tax is internal-only and guards /v1/tax/calculate with
                // @PreAuthorize('tax:calculate'). Propagate the required authority via the
                // gateway authorities header for this service-to-service call (see
                // GatewayAuthoritiesFilter and the pos-accounting client pattern).
                .header("X-User", "pos-invoice")
                .header("X-Authorities", "tax:calculate")
                .body(Map.of("subtotal", subtotal, "partyId", partyId == null ? "" : partyId))
                .retrieve()
                .body(TaxCalculationResponse.class);

        if (response == null) {
            throw new IllegalStateException("Tax service returned a null response for tax calculation");
        }

        BigDecimal taxAmount = response.getTaxAmount();
        return safeMoney(taxAmount, BigDecimal.ZERO);
    }

    @NonNull
    private BigDecimal safeMoney(@Nullable BigDecimal value, @NonNull BigDecimal fallback) {
        if (value == null) {
            log.warn("Tax service returned null taxAmount, using fallback value: {}", fallback);
            return fallback.setScale(4, RoundingMode.HALF_UP);
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private String maskForLog(Object value) {
        if (value == null) {
            return "null";
        }
        String sanitized =
                value.toString().replace('\r', '_').replace('\n', '_').replace('\t', '_');
        int length = sanitized.length();
        if (length <= 4) {
            return "****";
        }
        return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
    }
}
