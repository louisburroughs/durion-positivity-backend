package com.positivity.warranty.internal.client;

import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * RestClient implementation of {@link VehicleInventoryClient}.
 * Calls {@code GET /v1/vehicle-registry/{vehicleId}} on pos-vehicle-inventory.
 *
 * <p>The endpoint is {@code @PreAuthorize("isAuthenticated()")}; X-User/X-Authorities
 * are still required for service-to-service traffic.
 */
@Slf4j
@Component
public class VehicleInventoryClientImpl implements VehicleInventoryClient {

    private static final String SERVICE_USER = "pos-warranty-service";
    private static final String AUTHORITIES = "warranty:claim:view";

    private final RestClient restClient;
    private final String basePath;

    public VehicleInventoryClientImpl(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.vehicle-inventory.service-id:vehicle-inventory}") String serviceId,
            @Value("${pos.vehicle-inventory.base-path:/v1/vehicle-registry}") String basePath) {
        this.basePath = basePath;
        this.restClient = restClientBuilder.baseUrl("http://" + serviceId).build();
    }

    @Override
    public @NonNull Optional<VehicleSnapshot> getVehicle(@NonNull UUID vehicleId) {
        try {
            VehicleResponseWire response = restClient
                    .get()
                    .uri(basePath + "/{vehicleId}", vehicleId)
                    .header("X-User", SERVICE_USER)
                    .header("X-Authorities", AUTHORITIES)
                    .retrieve()
                    .body(VehicleResponseWire.class);
            if (response == null) {
                log.warn("vehicle-inventory returned empty body for vehicleId={}", vehicleId);
                return Optional.empty();
            }
            return Optional.of(new VehicleSnapshot(response.vin(), response.odometerValue(), response.odometerUnit()));
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                log.debug("Vehicle {} not found in vehicle-inventory", vehicleId);
            } else {
                log.warn(
                        "vehicle-inventory lookup failed for vehicleId={}: HTTP {}",
                        vehicleId,
                        ex.getStatusCode().value());
            }
            return Optional.empty();
        } catch (RestClientException ex) {
            log.warn("vehicle-inventory unreachable for vehicleId={}: {}", vehicleId, ex.getMessage());
            return Optional.empty();
        }
    }

    // -----------------------------------------------------------------------
    // Wire shape (mirrors the fields of com.positivity.shared.dto.VehicleResponse
    // that warranty needs, incl. the additive odometerValue/odometerUnit fields;
    // unknown properties are ignored by default)
    // -----------------------------------------------------------------------

    private record VehicleResponseWire(UUID vehicleId, String vin, Integer odometerValue, String odometerUnit) {}
}
