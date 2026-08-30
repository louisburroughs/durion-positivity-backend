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
public class CatalogFacadeTool {

    private final RestClient restClient;
    private final String productUriTemplate;
    private final String catalogSearchUriTemplate;
    private final String categoryUriTemplate;

    public CatalogFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.catalog.base-url}") @NonNull String baseUrl,
            @Value("${pos.catalog.product-uri-template}") @NonNull String productUriTemplate,
            @Value("${pos.catalog.search-uri-template}") @NonNull String catalogSearchUriTemplate,
            @Value("${pos.catalog.category-uri-template}") @NonNull String categoryUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.productUriTemplate = productUriTemplate;
        this.catalogSearchUriTemplate = catalogSearchUriTemplate;
        this.categoryUriTemplate = categoryUriTemplate;
    }

    @Tool(
            description = "Get catalog product details by product id (UUID). Returns the base product record "
                    + "without location-specific pricing.")
    public String getProduct(@ToolParam(description = "The product id (UUID)") @NonNull String productId) {
        return restClient
                .get()
                .uri(productUriTemplate, Map.of("productId", productId))
                .retrieve()
                .body(String.class);
    }

    @Tool(
            description = "Search catalog products by free-text query matching name, SKU, part number, or "
                    + "keyword. Returns only the first page of matches (default size 20).")
    public String searchCatalog(@ToolParam(description = "Search query for catalog products") @NonNull String query) {
        return restClient
                .get()
                .uri(catalogSearchUriTemplate, Map.of("query", query))
                .retrieve()
                .body(String.class);
    }

    @Tool(
            description = "List catalog products in a category. category is the category name or code used by "
                    + "the product search's category filter. Returns only the first page of matches (default "
                    + "size 20).")
    public String getCatalogByCategory(
            @ToolParam(description = "Catalog category name or code") @NonNull String category) {
        return restClient
                .get()
                .uri(categoryUriTemplate, Map.of("category", category))
                .retrieve()
                .body(String.class);
    }
}
