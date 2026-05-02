package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class VehicleFacadeTool {

    private final RestClient restClient;

    public VehicleFacadeTool(
            RestClient.Builder restClientBuilder,
            @Value("${pos.vehicle.base-url:http://pos-customer/v1/vehicles}") @NonNull String baseUrl) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
    }

    @Tool("Get vehicle details by vehicle ID")
    public String getVehicle(@P("The vehicle ID") @NonNull String vehicleId) {
        return restClient.get().uri("/{vehicleId}", vehicleId).retrieve().body(String.class);
    }

    @Tool("Search vehicles by VIN, make, model, year, or plate")
    public String searchVehicles(@P("Search query for vehicles") @NonNull String query) {
        return restClient
                .get()
                .uri(uriBuilder ->
                        uriBuilder.path("/search").queryParam("q", query).build())
                .retrieve()
                .body(String.class);
    }

    @Tool("Get all vehicles associated with a specific customer")
    public String getVehiclesByCustomer(@P("The customer ID") @NonNull String customerId) {
        return restClient
                .get()
                .uri("/customer/{customerId}", customerId)
                .retrieve()
                .body(String.class);
    }
}
