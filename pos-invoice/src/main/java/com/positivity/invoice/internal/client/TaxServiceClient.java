package com.positivity.invoice.internal.client;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationRequest.TaxAddress;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxLineItem;
import com.positivity.tax.common.enums.TaxReferenceType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
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

    private static final String DEFAULT_CURRENCY = "USD";

    private final RestClient restClient;

    public TaxServiceClient(
            RestClient.Builder restClientBuilder,
            @Value("${invoice.tax.base-url:http://pos-tax:8091/v1/tax}") String taxServiceBaseUrl) {
        this.restClient = restClientBuilder.baseUrl(taxServiceBaseUrl).build();
    }

    /**
     * Calculate tax for an invoice against the shop-location jurisdiction.
     *
     * @param lineItems   the taxable line items
     * @param destination the tax jurisdiction address (shop location)
     * @param referenceId the source invoice/workorder identifier, for traceability
     * @return the total tax amount
     */
    @NonNull
    public BigDecimal calculateTax(
            @NonNull List<TaxLineItem> lineItems, @NonNull TaxAddress destination, @Nullable UUID referenceId) {
        if (log.isDebugEnabled()) {
            log.debug(
                    "Calculating tax for {} line item(s) in jurisdiction {}/{}",
                    lineItems.size(),
                    destination.getCountryCode(),
                    destination.getPostalCode());
        }

        TaxCalculationRequest request = TaxCalculationRequest.builder()
                .lineItems(lineItems)
                .destinationAddress(destination)
                .currencyCode(DEFAULT_CURRENCY)
                .referenceId(referenceId)
                .referenceType(TaxReferenceType.INVOICE)
                .build();

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
                .body(request)
                .retrieve()
                .body(TaxCalculationResponse.class);

        if (response == null) {
            throw new IllegalStateException("Tax service returned a null response for tax calculation");
        }

        return safeMoney(response.getTotalTax(), BigDecimal.ZERO);
    }

    @NonNull
    private BigDecimal safeMoney(@Nullable BigDecimal value, @NonNull BigDecimal fallback) {
        if (value == null) {
            log.warn("Tax service returned null totalTax, using fallback value: {}", fallback);
            return fallback.setScale(4, RoundingMode.HALF_UP);
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }
}
