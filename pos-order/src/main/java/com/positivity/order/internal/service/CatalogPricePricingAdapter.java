package com.positivity.order.internal.service;

import com.positivity.order.internal.client.PricingPort;
import com.positivity.order.internal.client.PricingQuote;
import com.positivity.order.internal.entity.ExtProduct;
import com.positivity.order.internal.repository.ExtProductRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * {@link PricingPort} backed by the {@code ext_product} replica (SKU → productId/description) and
 * pos-price's quote API (parity story B1). Lives in {@code internal.service} because it reads the
 * replica repository (module ArchUnit rule: repositories are service-layer only).
 *
 * <p>Known gap flagged to the pricing domain: {@code POST /v1/price/quotes} returns neither a
 * pricing-snapshot id nor structured promotion identifiers, so the rule breakdown JSON is
 * persisted as the line-level audit artifact instead. The quote request's required
 * {@code customerTierId} is served from configuration ({@code pos.order.pricing.default-customer-tier-id})
 * until a customer→tier mapping feed exists — the {@code ext_customer} replica carries only the
 * tier <em>name</em>.
 */
@Component
@Slf4j
public class CatalogPricePricingAdapter implements PricingPort {

    private final ExtProductRepository extProductRepository;
    private final RestClient priceServiceRestClient;
    private final Clock clock;
    private final boolean eventFeedEnabled;
    private final UUID defaultCustomerTierId;

    public CatalogPricePricingAdapter(
            ExtProductRepository extProductRepository,
            @Qualifier("priceServiceRestClient") RestClient priceServiceRestClient,
            Clock clock,
            @Value("${pos.order.kafka.enabled:false}") boolean eventFeedEnabled,
            @Value("${pos.order.pricing.default-customer-tier-id:00000000-0000-0000-0000-000000000000}")
                    UUID defaultCustomerTierId) {
        this.extProductRepository = extProductRepository;
        this.priceServiceRestClient = priceServiceRestClient;
        this.clock = clock;
        this.eventFeedEnabled = eventFeedEnabled;
        this.defaultCustomerTierId = defaultCustomerTierId;
    }

    @Override
    public @NonNull PricingQuote quoteForSku(
            @NonNull String sku, int quantity, @Nullable UUID locationId, @Nullable UUID customerId) {
        if (!eventFeedEnabled || extProductRepository.count() == 0) {
            // Cold replica: the module cannot yet distinguish unknown from unsynced SKUs; degrade
            // to the permissioned manual-price path instead of guessing.
            log.warn("Product replica cold; pricing unavailable for sku {}", sku);
            return PricingQuote.unavailable();
        }
        Optional<ExtProduct> product = extProductRepository.findFirstBySkuIgnoreCaseAndActiveTrue(sku);
        if (product.isEmpty()) {
            return PricingQuote.unknownSku();
        }
        return quoteProduct(product.get(), quantity, locationId);
    }

    private PricingQuote quoteProduct(ExtProduct product, int quantity, @Nullable UUID locationId) {
        try {
            JsonNode response = priceServiceRestClient
                    .post()
                    .uri("/v1/price/quotes")
                    .header("X-User", "pos-order")
                    .header("X-Authorities", "price:quote")
                    .body(Map.of(
                            "productId",
                            product.getProductId(),
                            "quantity",
                            quantity,
                            "locationId",
                            locationId != null ? locationId : new UUID(0L, 0L),
                            "customerTierId",
                            defaultCustomerTierId,
                            "effectiveTimestamp",
                            Instant.now(clock).toString()))
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || response.path("unitPrice").path("amount").isMissingNode()) {
                log.warn("pos-price returned no unit price for product {}", product.getProductId());
                return PricingQuote.unavailable();
            }
            BigDecimal unitPrice = response.path("unitPrice").path("amount").decimalValue(BigDecimal.ZERO);
            JsonNode breakdown = response.path("pricingBreakdown");
            return new PricingQuote(
                    PricingQuote.Status.PRICED,
                    unitPrice,
                    product.getProductId(),
                    product.getName() != null ? product.getName() : product.getSku(),
                    product.getTrackingLevel(),
                    breakdown.isMissingNode() || breakdown.isNull() ? null : breakdown.toString());
        } catch (HttpClientErrorException.NotFound notFound) {
            // Product known to the catalog replica but not priced: surface as unknown so the
            // clerk gets a clear signal rather than a silent fallback price.
            return PricingQuote.unknownSku();
        } catch (Exception e) {
            log.warn("pos-price unreachable for product {}: {}", product.getProductId(), e.getMessage());
            return PricingQuote.unavailable();
        }
    }
}
