package com.positivity.customer.internal.client;

import com.positivity.shared.dto.CreateVehicleRequest;
import com.positivity.shared.dto.VehicleResponse;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Client for interacting with the pos-vehicle-inventory service.
 * Handles vehicle CRUD operations by delegating to the vehicle registry.
 */
@Slf4j
@Component
public class VehicleInventoryClient {

    private static final String VEHICLE_BY_ID_PATH = "/vehicle-inventory/v1/vehicles/{vehicleId}";

    private final RestClient restClient;

    public VehicleInventoryClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.vehicle-inventory.base-url:http://api-gateway}") String vehicleInventoryBaseUrl) {
        this.restClient = restClientBuilder.baseUrl(vehicleInventoryBaseUrl).build();
        log.info("VehicleInventoryClient initialized with baseUrl: {}", vehicleInventoryBaseUrl);
    }

    /**
     * Creates a new vehicle in the vehicle inventory.
     */
    public VehicleResponse createVehicle(@NonNull CreateVehicleRequest request) {
        log.debug("Creating vehicle with VIN: {}", request.getVin());

        try {
            VehicleResponse response = restClient
                    .post()
                    .uri("/vehicle-inventory/v1/vehicles")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(VehicleResponse.class);

            log.info("Successfully created vehicle: {}", response.getVehicleId());
            return response;
        } catch (Exception e) {
            log.error("Failed to create vehicle with VIN: {}", request.getVin(), e);
            throw new RuntimeException("Failed to create vehicle: " + e.getMessage(), e);
        }
    }

    /**
     * Retrieves a vehicle by its ID.
     */
    public Optional<VehicleResponse> getVehicle(@NonNull UUID vehicleId) {
        log.debug("Fetching vehicle with ID: {}", vehicleId);

        try {
            VehicleResponse response = restClient
                    .get()
                    .uri(VEHICLE_BY_ID_PATH, vehicleId)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, clientResponse) -> {
                        if (clientResponse.getStatusCode().value() == 404) {
                            log.debug("Vehicle not found: {}", vehicleId);
                        }
                    })
                    .body(VehicleResponse.class);

            return Optional.ofNullable(response);
        } catch (Exception e) {
            log.warn("Failed to fetch vehicle {}: {}", vehicleId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Retrieves a vehicle by its VIN.
     */
    public Optional<VehicleResponse> getVehicleByVin(@NonNull String vin) {
        log.debug("Fetching vehicle with VIN: {}", vin);

        try {
            VehicleResponse response = restClient
                    .get()
                    .uri("/vehicle-inventory/v1/vehicles/vin/{vin}", vin)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (request, clientResponse) -> {
                        if (clientResponse.getStatusCode().value() == 404) {
                            log.debug("Vehicle not found with VIN: {}", vin);
                        }
                    })
                    .body(VehicleResponse.class);

            return Optional.ofNullable(response);
        } catch (Exception e) {
            log.warn("Failed to fetch vehicle by VIN {}: {}", vin, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Updates a vehicle.
     */
    public VehicleResponse updateVehicle(@NonNull UUID vehicleId, @NonNull CreateVehicleRequest request) {
        log.debug("Updating vehicle with ID: {}", vehicleId);

        try {
            VehicleResponse response = restClient
                    .put()
                    .uri(VEHICLE_BY_ID_PATH, vehicleId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(VehicleResponse.class);

            log.info("Successfully updated vehicle: {}", vehicleId);
            return response;
        } catch (Exception e) {
            log.error("Failed to update vehicle {}", vehicleId, e);
            throw new RuntimeException("Failed to update vehicle: " + e.getMessage(), e);
        }
    }

    /**
     * Deletes (deactivates) a vehicle.
     */
    public void deleteVehicle(@NonNull UUID vehicleId) {
        log.debug("Deleting vehicle with ID: {}", vehicleId);

        try {
            restClient.delete().uri(VEHICLE_BY_ID_PATH, vehicleId).retrieve().toBodilessEntity();

            log.info("Successfully deleted vehicle: {}", vehicleId);
        } catch (Exception e) {
            log.error("Failed to delete vehicle {}", vehicleId, e);
            throw new RuntimeException("Failed to delete vehicle: " + e.getMessage(), e);
        }
    }
}
