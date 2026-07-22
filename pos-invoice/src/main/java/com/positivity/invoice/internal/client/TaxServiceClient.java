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
     * Calculate tax for an invoice against the shop-location jurisdiction, returning the total.
     *
     * @param lineItems   the taxable line items
     * @param destination the tax jurisdiction address (shop location)
     * @param referenceId the source invoice/workorder identifier, for traceability
     * @return the total tax amount
     */
    @NonNull
    public BigDecimal calculateTax(
            @NonNull List<TaxLineItem> lineItems, @NonNull TaxAddress destination, @Nullable UUID referenceId) {
        return safeMoney(
                calculateTaxDetailed(lineItems, destination, referenceId).getTotalTax(), BigDecimal.ZERO);
    }

    /**
     * Calculate tax and return the full {@link TaxCalculationResponse} — including the per-line ×
     * per-jurisdiction breakdown ({@code LineItemTax.jurisdictions[]}) that story T5 persists.
     *
     * <p>Unlike {@link #calculateTax}, this does NOT truncate to the scalar total; callers that
     * only need the rollup should prefer {@link #calculateTax}.
     *
     * @param lineItems   the taxable line items
     * @param destination the tax jurisdiction address (shop location)
     * @param referenceId the source invoice/workorder identifier, for traceability
     * @return the full tax calculation response
     */
    @NonNull
    public TaxCalculationResponse calculateTaxDetailed(
            @NonNull List<TaxLineItem> lineItems, @NonNull TaxAddress destination, @Nullable UUID referenceId) {
        return calculateTaxDetailed(lineItems, destination, referenceId, false);
    }

    /**
     * Calculate tax with an explicit lifecycle intent (#985).
     *
     * <p>{@code committable=true} marks the calculation as destined for the provider commit/void
     * lifecycle: pos-tax then creates a PERSISTED provider document (AvaTax {@code SalesInvoice},
     * uncommitted) under {@code code == referenceId}, so the later
     * {@code /transactions/{referenceId}/commit} resolves. A committable call therefore REQUIRES
     * a non-null {@code referenceId} (the invoice id) — pos-tax rejects it otherwise.
     *
     * @param lineItems   the taxable line items
     * @param destination the tax jurisdiction address (shop location)
     * @param referenceId the source invoice identifier (the provider document code)
     * @param committable whether this calculation binds to the provider commit/void lifecycle
     * @return the full tax calculation response
     */
    @NonNull
    public TaxCalculationResponse calculateTaxDetailed(
            @NonNull List<TaxLineItem> lineItems,
            @NonNull TaxAddress destination,
            @Nullable UUID referenceId,
            boolean committable) {
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
                .committable(committable)
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

        return response;
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
