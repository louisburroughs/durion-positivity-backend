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
public class VehicleFacadeTool {

    private final RestClient restClient;
    private final String vehicleUriTemplate;
    private final String vehicleSearchUriTemplate;
    private final String customerVehiclesUriTemplate;

    public VehicleFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.vehicle.base-url}") @NonNull String baseUrl,
            @Value("${pos.vehicle.vehicle-uri-template}") @NonNull String vehicleUriTemplate,
            @Value("${pos.vehicle.search-uri-template}") @NonNull String vehicleSearchUriTemplate,
            @Value("${pos.vehicle.customer-vehicles-uri-template}") @NonNull String customerVehiclesUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.vehicleUriTemplate = vehicleUriTemplate;
        this.vehicleSearchUriTemplate = vehicleSearchUriTemplate;
        this.customerVehiclesUriTemplate = customerVehiclesUriTemplate;
    }

    @Tool(
            description = "Get vehicle registry details by vehicle id (UUID). Deactivated vehicles are "
                    + "returned too — check the isActive field.")
    public String getVehicle(@ToolParam(description = "The vehicle id (UUID)") @NonNull String vehicleId) {
        return restClient
                .get()
                .uri(vehicleUriTemplate, Map.of("vehicleId", vehicleId))
                .retrieve()
                .body(String.class);
    }

    @Tool(
            description = "Search vehicles by VIN, make, model, year, or plate. The query must be at least 3 "
                    + "characters; the vehicle service additionally requires 6 characters for VIN-shaped "
                    + "fragments. Returns at most the first 25 matches (the service's default result limit).")
    public String searchVehicles(
            @ToolParam(description = "Search query, minimum 3 characters (6 for VIN fragments)") @NonNull
                    String query) {
        String trimmed = query.trim();
        if (trimmed.length() < 3) {
            throw new IllegalArgumentException(
                    "Search query '" + trimmed + "' is too short: at least 3 characters required"
                            + " (VIN-shaped fragments need 6, enforced by the vehicle service)");
        }
        return restClient
                .get()
                .uri(vehicleSearchUriTemplate, Map.of("query", trimmed))
                .retrieve()
                .body(String.class);
    }

    @Tool(description = "Get all vehicles associated with a customer by the customer's party id (UUID)")
    public String getVehiclesByCustomer(
            @ToolParam(description = "The customer's party id (UUID)") @NonNull String customerId) {
        return restClient
                .get()
                .uri(customerVehiclesUriTemplate, Map.of("customerId", customerId))
                .retrieve()
                .body(String.class);
    }
}
