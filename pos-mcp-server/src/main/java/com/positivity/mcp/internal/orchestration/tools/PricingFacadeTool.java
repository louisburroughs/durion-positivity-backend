package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PricingFacadeTool {

    private final RestClient restClient;
    private final String skuPriceUriTemplate;
    private final String pricingSearchUriTemplate;
    private final String priceListUriTemplate;

    public PricingFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.pricing.base-url}") @NonNull String baseUrl,
            @Value("${pos.pricing.sku-price-uri-template}") @NonNull String skuPriceUriTemplate,
            @Value("${pos.pricing.search-uri-template}") @NonNull String pricingSearchUriTemplate,
            @Value("${pos.pricing.price-list-uri-template}") @NonNull String priceListUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.skuPriceUriTemplate = skuPriceUriTemplate;
        this.pricingSearchUriTemplate = pricingSearchUriTemplate;
        this.priceListUriTemplate = priceListUriTemplate;
    }

    @Tool("Get current price details for a specific SKU")
    public String getPriceForSku(@P("The SKU to price") @NonNull String sku) {
        return restClient
                .get()
                .uri(skuPriceUriTemplate, Map.of("sku", sku))
                .retrieve()
                .body(String.class);
    }

    @Tool("Search pricing data by SKU, description, or pricing rule")
    public String searchPricing(@P("Search query for pricing") @NonNull String query) {
        return restClient
                .get()
                .uri(pricingSearchUriTemplate, Map.of("query", query))
                .retrieve()
                .body(String.class);
    }

    @Tool("Get a full price list by price list ID")
    public String getPriceList(@P("The price list ID") @NonNull String priceListId) {
        return restClient
                .get()
                .uri(priceListUriTemplate, Map.of("priceListId", priceListId))
                .retrieve()
                .body(String.class);
    }
}
