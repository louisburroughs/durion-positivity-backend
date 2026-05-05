package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
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

    @Tool("Get vehicle details by vehicle ID")
    public String getVehicle(@P("The vehicle ID") @NonNull String vehicleId) {
        return restClient
                .get()
                .uri(vehicleUriTemplate, Map.of("vehicleId", vehicleId))
                .retrieve()
                .body(String.class);
    }

    @Tool("Search vehicles by VIN, make, model, year, or plate")
    public String searchVehicles(@P("Search query for vehicles") @NonNull String query) {
        return restClient
                .get()
                .uri(vehicleSearchUriTemplate, Map.of("query", query))
                .retrieve()
                .body(String.class);
    }

    @Tool("Get all vehicles associated with a specific customer")
    public String getVehiclesByCustomer(@P("The customer ID") @NonNull String customerId) {
        return restClient
                .get()
                .uri(customerVehiclesUriTemplate, Map.of("customerId", customerId))
                .retrieve()
                .body(String.class);
    }
}
