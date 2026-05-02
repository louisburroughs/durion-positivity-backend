package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class InventoryFacadeTool {

    private final RestClient restClient;

    public InventoryFacadeTool(
            RestClient.Builder restClientBuilder,
            @Value("${pos.inventory.base-url:http://pos-inventory/v1/inventory}") @NonNull String baseUrl) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
    }

    @Tool("Check current stock level for a product by SKU number")
    public String checkStock(@P("The SKU number to look up") @NonNull String sku) {
        return restClient.get().uri("/stock/{sku}", sku).retrieve().body(String.class);
    }

    @Tool("Search inventory by product name or partial SKU")
    public String searchInventory(@P("Search term: product name or partial SKU") @NonNull String query) {
        return restClient
                .get()
                .uri(uriBuilder ->
                        uriBuilder.path("/search").queryParam("q", query).build())
                .retrieve()
                .body(String.class);
    }

    @Tool("Get stock levels for all products at a specific store location")
    public String getLocationStock(@P("Store location ID") @NonNull String locationId) {
        return restClient
                .get()
                .uri("/locations/{locationId}/stock", locationId)
                .retrieve()
                .body(String.class);
    }
}
