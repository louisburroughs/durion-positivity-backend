package com.positivity.location.internal.client;

import java.util.UUID;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Client for reading location-level inventory facts from pos-inventory.
 */
@Slf4j
@Component
public class LocationInventoryInquiryClient {

    private final RestClient restClient;

    public LocationInventoryInquiryClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.inventory.service-id:inventory}") String inventoryServiceId) {
        this.restClient = restClientBuilder.baseUrl("http://" + inventoryServiceId).build();
    }

    public int getOnHandQuantity(@NonNull UUID storageLocationId) {
        try {
            LocationInventoryInquiryResponse response = restClient
                    .get()
                    .uri("/v1/inventory/locations/{storageLocationId}/inventory-inquiry", storageLocationId)
                    .header("X-User", "pos-location")
                    .header("X-Authorities", "inventory:on_hand:view")
                    .retrieve()
                    .body(LocationInventoryInquiryResponse.class);

            if (response == null) {
                throw new IllegalStateException("Inventory inquiry returned no payload");
            }
            return response.getOnHandQuantity();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch inventory for storage location {}", storageLocationId, e);
            throw new IllegalStateException("Inventory inquiry failed for location " + storageLocationId, e);
        }
    }

    @Data
    private static class LocationInventoryInquiryResponse {
        private int onHandQuantity;
    }
}
