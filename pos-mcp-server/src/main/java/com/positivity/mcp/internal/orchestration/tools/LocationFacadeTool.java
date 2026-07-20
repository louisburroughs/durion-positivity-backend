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
public class LocationFacadeTool {

    private final RestClient restClient;
    private final String locationUriTemplate;
    private final String locationSearchUriTemplate;
    private final String locationInventoryUriTemplate;

    public LocationFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.location.base-url}") @NonNull String baseUrl,
            @Value("${pos.location.location-uri-template}") @NonNull String locationUriTemplate,
            @Value("${pos.location.search-uri-template}") @NonNull String locationSearchUriTemplate,
            @Value("${pos.location.inventory-uri-template}") @NonNull String locationInventoryUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.locationUriTemplate = locationUriTemplate;
        this.locationSearchUriTemplate = locationSearchUriTemplate;
        this.locationInventoryUriTemplate = locationInventoryUriTemplate;
    }

    @Tool(description = "Get location details by location ID")
    public String getLocation(@ToolParam(description = "The location ID") @NonNull String locationId) {
        return restClient
                .get()
                .uri(locationUriTemplate, Map.of("locationId", locationId))
                .retrieve()
                .body(String.class);
    }

    @Tool(description = "Search locations by name, city, code, or attributes")
    public String searchLocations(@ToolParam(description = "Search query for locations") @NonNull String query) {
        return restClient
                .get()
                .uri(locationSearchUriTemplate, Map.of("query", query))
                .retrieve()
                .body(String.class);
    }

    @Tool(description = "Get inventory context for a specific location")
    public String getLocationInventory(@ToolParam(description = "The location ID") @NonNull String locationId) {
        return restClient
                .get()
                .uri(locationInventoryUriTemplate, Map.of("locationId", locationId))
                .retrieve()
                .body(String.class);
    }
}
