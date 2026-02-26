package com.positivity.catalog.internal.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

/**
 * RestClient implementation for {@link InventoryClient}.
 * Calls {@code GET /v1/inventory/availability/query} on the pos-inventory
 * service.
 *
 * <p>
 * pos-inventory availability endpoint is protected by
 * {@code @PreAuthorize("hasAnyAuthority('inventory:availability:read',...)")}
 * so the {@code X-Authorities} header is set for service-to-service calls.
 *
 * Issue: CAP-247 Story #16
 */
@Slf4j
@Component
public class InventoryClientImpl implements InventoryClient {

    private final RestClient restClient;

    public InventoryClientImpl(
            RestClient.Builder restClientBuilder,
            @Value("${pos.inventory.base-url:http://pos-inventory}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    @Override
    public Optional<AvailabilityClientResponse> fetchAvailability(String productSku, UUID locationId) {
        log.debug("Fetching availability: productSku={}, locationId={}", productSku, locationId);

        AvailabilityServiceResponse response = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/inventory/availability/query")
                        .queryParam("productSku", productSku)
                        .queryParam("locationId", locationId)
                        .build())
                .header("X-User", "pos-catalog-service")
                .header("X-Authorities", "inventory:availability:read")
                .retrieve()
                .body(AvailabilityServiceResponse.class);

        if (response == null) {
            log.warn("Inventory service returned null for productSku={}", productSku);
            return Optional.empty();
        }

        return Optional.of(new AvailabilityClientResponse(
                response.onHandQuantity(),
                response.allocatedQuantity(),
                response.availableToPromiseQuantity(),
                response.unitOfMeasure()));
    }

    // -----------------------------------------------------------------------
    // Internal response shape (mirrors pos-inventory API contract)
    // -----------------------------------------------------------------------

    private record AvailabilityServiceResponse(
            int onHandQuantity,
            int allocatedQuantity,
            int availableToPromiseQuantity,
            String unitOfMeasure) {
    }
}
