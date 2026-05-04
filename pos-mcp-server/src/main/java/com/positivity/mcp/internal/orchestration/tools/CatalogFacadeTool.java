package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class CatalogFacadeTool {

    private final RestClient restClient;

    public CatalogFacadeTool(
            RestClient.Builder restClientBuilder,
            @Value("${pos.catalog.base-url:http://pos-catalog/v1/catalog}") @NonNull String baseUrl) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
    }

    @Tool("Get product details from the catalog by product ID")
    public String getProduct(@P("The product ID") @NonNull String productId) {
        return restClient
                .get()
                .uri("/products/{productId}", productId)
                .retrieve()
                .body(String.class);
    }

    @Tool("Search the catalog by name, SKU, part number, or keyword")
    public String searchCatalog(@P("Search query for catalog") @NonNull String query) {
        return restClient
                .get()
                .uri(uriBuilder ->
                        uriBuilder.path("/search").queryParam("q", query).build())
                .retrieve()
                .body(String.class);
    }

    @Tool("Get catalog products filtered by category")
    public String getCatalogByCategory(@P("Catalog category name or code") @NonNull String category) {
        return restClient
                .get()
                .uri("/categories/{category}", category)
                .retrieve()
                .body(String.class);
    }
}
