package com.positivity.mcp.internal.orchestration.tools;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
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

    @Tool(
            description = "Check availability for a product by its exact SKU across the network. Returns "
                    + "available/on-order quantities for the SKU; 404 when the SKU has no stock records.")
    public String checkStock(@ToolParam(description = "The exact product SKU") @NonNull String productSku) {
        return restClient
                .get()
                .uri(stockUriTemplate, Map.of("productSku", productSku))
                .retrieve()
                .body(String.class);
    }

    @Tool(
            description = "Look up availability for an exact product SKU, optionally narrowed to one warehouse "
                    + "location. This is an exact-SKU lookup, not a free-text search — the SKU must match "
                    + "exactly. locationId, when given, must be a location UUID.")
    public String searchInventory(
            @ToolParam(description = "The exact product SKU") @NonNull String productSku,
            @ToolParam(description = "Optional location id (UUID) to narrow to one warehouse", required = false)
                    String locationId) {
        String template = inventorySearchUriTemplate;
        Map<String, String> uriParams = new HashMap<>();
        uriParams.put("productSku", productSku);
        if (locationId != null && !locationId.isBlank()) {
            // The availability endpoint rejects locationId unless sourceType=WAREHOUSE accompanies it.
            template = template + "&locationId={locationId}&sourceType=WAREHOUSE";
            uriParams.put("locationId", locationId);
        }
        return restClient.get().uri(template, uriParams).retrieve().body(String.class);
    }

    @Tool(
            description = "Get the on-hand inventory inquiry for a store location by location id (UUID): "
                    + "per-product on-hand stock at that site.")
    public String getLocationStock(@ToolParam(description = "Store location id (UUID)") @NonNull String locationId) {
        return restClient
                .get()
                .uri(locationStockUriTemplate, Map.of("locationId", locationId))
                .retrieve()
                .body(String.class);
    }
}
