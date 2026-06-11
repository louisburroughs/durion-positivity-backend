package com.positivity.inventory.internal.client;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Client for validating storage locations against pos-location source-of-truth.
 */
@Component
public class StorageLocationValidationClient {

    private static final Logger log = LoggerFactory.getLogger(StorageLocationValidationClient.class);

    private final RestClient restClient;

    public StorageLocationValidationClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.location.service-id:location}") String locationServiceId) {
        this.restClient =
                restClientBuilder.baseUrl("http://" + locationServiceId).build();
    }

    @NonNull
    public StorageLocationValidation getStorageLocationValidation(@NonNull String storageLocationId) {
        UUID parsedId;
        try {
            parsedId = UUID.fromString(storageLocationId);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("destinationLocationId must be a valid UUID", ex);
        }

        StorageLocationValidation response = restClient
                .get()
                .uri("/v1/storage-locations/{storageLocationId}/validation", parsedId)
                .header("X-User", "pos-inventory")
                .header("X-Authorities", "location:read")
                .retrieve()
                .body(StorageLocationValidation.class);

        if (response == null) {
            log.warn("Storage location validation returned null payload for location {}", storageLocationId);
            throw new IllegalStateException("Location validation service returned no data");
        }

        return response;
    }

    public static class StorageLocationValidation {
        private UUID storageLocationId;
        private UUID siteId;
        private boolean exists;
        private boolean active;
        private Integer maxUnitCapacity;

        public UUID getStorageLocationId() {
            return storageLocationId;
        }

        public void setStorageLocationId(UUID storageLocationId) {
            this.storageLocationId = storageLocationId;
        }

        public UUID getSiteId() {
            return siteId;
        }

        public void setSiteId(UUID siteId) {
            this.siteId = siteId;
        }

        public boolean isExists() {
            return exists;
        }

        public void setExists(boolean exists) {
            this.exists = exists;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(boolean active) {
            this.active = active;
        }

        public Integer getMaxUnitCapacity() {
            return maxUnitCapacity;
        }

        public void setMaxUnitCapacity(Integer maxUnitCapacity) {
            this.maxUnitCapacity = maxUnitCapacity;
        }
    }
}
