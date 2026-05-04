package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class InventoryFacadeTool {

    private final RestClient restClient;
    private final String stockUriTemplate;
    private final String inventorySearchUriTemplate;
    private final String locationStockUriTemplate;

    public InventoryFacadeTool(
            RestClient.Builder restClientBuilder,
            @Value("${pos.inventory.base-url}") @NonNull String baseUrl,
            @Value("${pos.inventory.stock-uri-template}") @NonNull String stockUriTemplate,
            @Value("${pos.inventory.search-uri-template}") @NonNull String inventorySearchUriTemplate,
            @Value("${pos.inventory.location-stock-uri-template}") @NonNull String locationStockUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.stockUriTemplate = stockUriTemplate;
        this.inventorySearchUriTemplate = inventorySearchUriTemplate;
        this.locationStockUriTemplate = locationStockUriTemplate;
    }

    @Tool("Check current stock level for a product by SKU number")
    public String checkStock(@P("The SKU number to look up") @NonNull String sku) {
        return restClient
                .get()
                .uri(stockUriTemplate, Map.of("sku", sku))
                .retrieve()
                .body(String.class);
    }

    @Tool("Search inventory by product name or partial SKU")
    public String searchInventory(@P("Search term: product name or partial SKU") @NonNull String query) {
        return restClient
                .get()
                .uri(inventorySearchUriTemplate, Map.of("query", query))
                .retrieve()
                .body(String.class);
    }

    @Tool("Get stock levels for all products at a specific store location")
    public String getLocationStock(@P("Store location ID") @NonNull String locationId) {
        return restClient
                .get()
                .uri(locationStockUriTemplate, Map.of("locationId", locationId))
                .retrieve()
                .body(String.class);
    }
}
