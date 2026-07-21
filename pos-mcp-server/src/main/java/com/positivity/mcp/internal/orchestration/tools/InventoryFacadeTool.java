package com.positivity.mcp.internal.orchestration.tools;

import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.annotation.Tool;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
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
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.inventory.base-url}") @NonNull String baseUrl,
            @Value("${pos.inventory.stock-uri-template}") @NonNull String stockUriTemplate,
            @Value("${pos.inventory.search-uri-template}") @NonNull String inventorySearchUriTemplate,
            @Value("${pos.inventory.location-stock-uri-template}") @NonNull String locationStockUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.stockUriTemplate = stockUriTemplate;
        this.inventorySearchUriTemplate = inventorySearchUriTemplate;
        this.locationStockUriTemplate = locationStockUriTemplate;
    }

    @Tool(description = "Check current stock level for a product by SKU number")
    public String checkStock(@ToolParam(description = "The SKU number to look up") @NonNull String sku) {
        return restClient
                .get()
                .uri(stockUriTemplate, Map.of("sku", sku))
                .retrieve()
                .body(String.class);
    }

    @Tool(description = "Search inventory by product name or partial SKU")
    public String searchInventory(@ToolParam(description = "Search term: product name or partial SKU") @NonNull String query) {
        return restClient
                .get()
                .uri(inventorySearchUriTemplate, Map.of("query", query))
                .retrieve()
                .body(String.class);
    }

    @Tool(description = "Get stock levels for all products at a specific store location")
    public String getLocationStock(@ToolParam(description = "Store location ID") @NonNull String locationId) {
        return restClient
                .get()
                .uri(locationStockUriTemplate, Map.of("locationId", locationId))
                .retrieve()
                .body(String.class);
    }
}
