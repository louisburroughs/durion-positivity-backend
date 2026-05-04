package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.Map;
import org.jspecify.annotations.NonNull;
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
            RestClient.Builder restClientBuilder,
            @Value("${pos.catalog.base-url}") @NonNull String baseUrl,
            @Value("${pos.catalog.product-uri-template}") @NonNull String productUriTemplate,
            @Value("${pos.catalog.search-uri-template}") @NonNull String catalogSearchUriTemplate,
            @Value("${pos.catalog.category-uri-template}") @NonNull String categoryUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.productUriTemplate = productUriTemplate;
        this.catalogSearchUriTemplate = catalogSearchUriTemplate;
        this.categoryUriTemplate = categoryUriTemplate;
    }

    @Tool("Get product details from the catalog by product ID")
    public String getProduct(@P("The product ID") @NonNull String productId) {
        return restClient
                .get()
                .uri(productUriTemplate, Map.of("productId", productId))
                .retrieve()
                .body(String.class);
    }

    @Tool("Search the catalog by name, SKU, part number, or keyword")
    public String searchCatalog(@P("Search query for catalog") @NonNull String query) {
        return restClient
                .get()
                .uri(catalogSearchUriTemplate, Map.of("query", query))
                .retrieve()
                .body(String.class);
    }

    @Tool("Get catalog products filtered by category")
    public String getCatalogByCategory(@P("Catalog category name or code") @NonNull String category) {
        return restClient
                .get()
                .uri(categoryUriTemplate, Map.of("category", category))
                .retrieve()
                .body(String.class);
    }
}
