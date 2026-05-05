package com.positivity.catalog.internal.client;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * RestClient implementation for {@link PricingClient}.
 * Calls {@code POST /v1/price/quotes} on the pos-price service.
 *
 * <p>
 * pos-price has no class-level &#64;PreAuthorize, so no authority headers
 * are required for service-to-service calls.
 *
 * Issue: CAP-247 Story #16
 */
@Slf4j
@Component
public class PricingClientImpl implements PricingClient {

    private final RestClient restClient;

    public PricingClientImpl(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.price.base-url:http://api-gateway}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    @Override
    public Optional<PriceQuoteClientResponse> fetchPrice(UUID productId, UUID locationId, UUID customerTierId) {
        log.debug("Fetching price quote: productId={}, locationId={}", productId, locationId);
        PriceQuoteRequest body = new PriceQuoteRequest(productId, 1, locationId, customerTierId);

        PriceQuoteServiceResponse response = restClient.post().uri("/v1/price/quotes").body(body).retrieve()
                .body(PriceQuoteServiceResponse.class);

        if (response == null || response.msrp() == null) {
            log.warn("Price service returned null for productId={}", productId);
            return Optional.empty();
        }

        if (response.msrp().amount() == null) {
            log.warn("Price service returned null msrp.amount for productId={}, degrading to unavailable", productId);
            return Optional.empty();
        }

        BigDecimal msrpAmount = response.msrp().amount();
        String msrpCurrency = response.msrp().currency() != null ? response.msrp().currency() : "USD";
        BigDecimal unitPriceAmt = response.unitPrice() != null && response.unitPrice().amount() != null
                ? response.unitPrice().amount()
                : msrpAmount;
        String priceSource = response.priceSource() != null ? response.priceSource() : "UNKNOWN";

        return Optional.of(new PriceQuoteClientResponse(msrpAmount, msrpCurrency, unitPriceAmt, priceSource));
    }

    // -----------------------------------------------------------------------
    // Internal request/response shapes (mirrors pos-price API contract)
    // -----------------------------------------------------------------------

    private record PriceQuoteRequest(UUID productId, int quantity, UUID locationId, UUID customerTierId) {
    }

    private record MoneyValue(BigDecimal amount, String currency) {
    }

    private record PriceQuoteServiceResponse(MoneyValue msrp, MoneyValue unitPrice, String priceSource) {
    }
}
