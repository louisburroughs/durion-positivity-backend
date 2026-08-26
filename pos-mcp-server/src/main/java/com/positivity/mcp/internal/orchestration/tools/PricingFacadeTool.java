package com.positivity.mcp.internal.orchestration.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.UncheckedIOException;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PricingFacadeTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final String productSearchUriTemplate;
    private final String effectivePriceUriTemplate;
    private final String promotionByCodeUriTemplate;
    private final String priceRestrictionsUriTemplate;
    private final String priceListUriTemplate;

    public PricingFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.pricing.base-url}") @NonNull String baseUrl,
            @Value("${pos.pricing.product-search-uri-template}") @NonNull String productSearchUriTemplate,
            @Value("${pos.pricing.effective-price-uri-template}") @NonNull String effectivePriceUriTemplate,
            @Value("${pos.pricing.promotion-by-code-uri-template}") @NonNull String promotionByCodeUriTemplate,
            @Value("${pos.pricing.price-restrictions-uri-template}") @NonNull String priceRestrictionsUriTemplate,
            @Value("${pos.pricing.price-list-uri-template}") @NonNull String priceListUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.productSearchUriTemplate = productSearchUriTemplate;
        this.effectivePriceUriTemplate = effectivePriceUriTemplate;
        this.promotionByCodeUriTemplate = promotionByCodeUriTemplate;
        this.priceRestrictionsUriTemplate = priceRestrictionsUriTemplate;
        this.priceListUriTemplate = priceListUriTemplate;
    }

    @Tool(
            description = "Get the current price for a SKU (exact, case-insensitive match). Returns a JSON "
                    + "envelope whose product section carries the matched product's id, name, lifecycle "
                    + "state, and active MSRP (msrpAmount/msrpCurrency, null when the product has no active "
                    + "MSRP). When a locationId (UUID) is also given, an effectivePrice section adds that "
                    + "location's effective price for the product. If no product matches the SKU, the tool "
                    + "returns {\"status\":\"not_found\"} with a message instead of the envelope.")
    public String getPriceForSku(
            @ToolParam(description = "The SKU to price (exact match, case-insensitive)") @NonNull String sku,
            @ToolParam(
                            description = "Optional location id (UUID) for a location-specific effective price",
                            required = false)
                    String locationId) {
        String searchBody;
        try {
            searchBody = restClient
                    .get()
                    .uri(productSearchUriTemplate, Map.of("sku", sku))
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException searchFailure) {
            // Render the failed search through the standard composition envelope (403 -> the
            // not_authorized section, anything else -> a bodyless error section).
            return ToolComposition.named("skuPrice")
                    .call("product", () -> {
                        throw searchFailure;
                    })
                    .require("product")
                    .render();
        }
        JsonNode product;
        try {
            product = firstMatchedProduct(searchBody);
        } catch (ToolComposition.LegFailure unreadable) {
            return ToolComposition.named("skuPrice")
                    .call("product", () -> {
                        throw unreadable;
                    })
                    .require("product")
                    .render();
        }
        if (product == null) {
            return notFound(sku);
        }
        String productSummary = condensedProduct(product);
        String productId = product.path("productId").asText("");
        ToolComposition composition = ToolComposition.named("skuPrice")
                .call("product", () -> productSummary)
                .require("product");
        if (locationId != null && !locationId.isBlank()) {
            composition.call("effectivePrice", () -> {
                if (productId.isBlank()) {
                    throw new ToolComposition.LegFailure("The catalog search row for SKU " + sku
                            + " carries no productId; " + "the location effective price cannot be looked up");
                }
                return restClient
                        .get()
                        .uri(effectivePriceUriTemplate, Map.of("locationId", locationId, "productId", productId))
                        .retrieve()
                        .body(String.class);
            });
        }
        return composition.render();
    }

    private static @Nullable JsonNode firstMatchedProduct(@Nullable String searchBody) {
        if (searchBody == null || searchBody.isBlank()) {
            return null;
        }
        JsonNode rows;
        try {
            rows = MAPPER.readTree(searchBody).path("data");
        } catch (JsonProcessingException notJson) {
            throw new ToolComposition.LegFailure("The catalog product search returned an unreadable response");
        }
        return rows.isArray() && !rows.isEmpty() ? rows.get(0) : null;
    }

    private static @NonNull String condensedProduct(@NonNull JsonNode product) {
        ObjectNode summary = MAPPER.createObjectNode();
        for (String field : new String[] {"productId", "sku", "name", "lifecycleState", "msrpAmount", "msrpCurrency"}) {
            summary.set(field, product.path(field).isMissingNode() ? NullNode.getInstance() : product.get(field));
        }
        return write(summary);
    }

    private static @NonNull String notFound(@NonNull String sku) {
        ObjectNode notFound = MAPPER.createObjectNode();
        notFound.put("status", "not_found");
        notFound.put("sku", sku);
        notFound.put(
                "message",
                "No catalog product matches SKU '" + sku + "' (the match is exact, "
                        + "case-insensitive); no price is available");
        return write(notFound);
    }

    private static @NonNull String write(@NonNull ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException("Failed to render SKU price JSON", exception);
        }
    }

    @Tool(
            description = "Look up a promotion offer by its exact promo code. There is no free-text promotion "
                    + "search — the code must match exactly.")
    public String getPromotionByCode(@ToolParam(description = "The exact promotion code") @NonNull String promoCode) {
        return restClient
                .get()
                .uri(promotionByCodeUriTemplate, Map.of("promoCode", promoCode))
                .retrieve()
                .body(String.class);
    }

    @Tool(description = "List all price restriction rules (price floors, ceilings, and sale eligibility rules)")
    public String listPriceRestrictions() {
        return restClient.get().uri(priceRestrictionsUriTemplate).retrieve().body(String.class);
    }

    @Tool(
            description = "Get a price book by its price book id (UUID), served by the catalog domain "
                    + "(the system of record for price books). There is no price book list — the id must be "
                    + "known.")
    public String getPriceList(@ToolParam(description = "The price book id (UUID)") @NonNull String priceBookId) {
        return restClient
                .get()
                .uri(priceListUriTemplate, Map.of("priceBookId", priceBookId))
                .retrieve()
                .body(String.class);
    }
}
