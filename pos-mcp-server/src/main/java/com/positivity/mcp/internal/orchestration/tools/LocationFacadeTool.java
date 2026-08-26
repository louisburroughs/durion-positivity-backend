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

    @Tool(
            description = "Search locations by name or code. The match is a case-insensitive contains-filter "
                    + "on the location's name and code fields; city or other attributes are not searched.")
    public String searchLocations(
            @ToolParam(description = "Location name or code (or part of it)") @NonNull String query) {
        String locations =
                restClient.get().uri(locationSearchUriTemplate).retrieve().body(String.class);
        return locations == null ? null : FacadeJsonSupport.filterLocationsByNameOrCode(locations, query);
    }

    @Tool(
            description = "Get the on-hand inventory inquiry for a location by location id (UUID): per-product "
                    + "on-hand stock at that site, served by the inventory domain.")
    public String getLocationInventory(@ToolParam(description = "The location id (UUID)") @NonNull String locationId) {
        return restClient
                .get()
                .uri(locationInventoryUriTemplate, Map.of("locationId", locationId))
                .retrieve()
                .body(String.class);
    }
}
