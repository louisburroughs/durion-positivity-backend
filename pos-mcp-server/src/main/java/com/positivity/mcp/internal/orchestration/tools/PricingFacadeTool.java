package com.positivity.mcp.internal.orchestration.tools;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PricingFacadeTool {

    private final RestClient restClient;
    private final String skuPriceUriTemplate;
    private final String promotionByCodeUriTemplate;
    private final String priceRestrictionsUriTemplate;
    private final String priceListUriTemplate;

    public PricingFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.pricing.base-url}") @NonNull String baseUrl,
            @Value("${pos.pricing.sku-price-uri-template}") @NonNull String skuPriceUriTemplate,
            @Value("${pos.pricing.promotion-by-code-uri-template}") @NonNull String promotionByCodeUriTemplate,
            @Value("${pos.pricing.price-restrictions-uri-template}") @NonNull String priceRestrictionsUriTemplate,
            @Value("${pos.pricing.price-list-uri-template}") @NonNull String priceListUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.skuPriceUriTemplate = skuPriceUriTemplate;
        this.promotionByCodeUriTemplate = promotionByCodeUriTemplate;
        this.priceRestrictionsUriTemplate = priceRestrictionsUriTemplate;
        this.priceListUriTemplate = priceListUriTemplate;
    }

    @Tool(description = "Get current price details for a specific SKU")
    public String getPriceForSku(@ToolParam(description = "The SKU to price") @NonNull String sku) {
        return restClient
                .get()
                .uri(skuPriceUriTemplate, Map.of("sku", sku))
                .retrieve()
                .body(String.class);
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
