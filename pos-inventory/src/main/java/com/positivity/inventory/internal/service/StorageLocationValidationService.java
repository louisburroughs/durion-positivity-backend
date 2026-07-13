package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage-location existence/active validation served from the {@code ext_storage_location}
 * replica (ADR-0044 §6, #892). Replaces the retired synchronous
 * {@code StorageLocationValidationClient}; a location missing from the replica yields the same
 * {@code exists=false} verdict the owner returned.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StorageLocationValidationService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final ExtStorageLocationReplicaRepository extStorageLocationReplicaRepository;

    @NonNull
    public StorageLocationValidation getStorageLocationValidation(@NonNull String storageLocationId) {
        UUID parsedId;
        try {
            parsedId = UUID.fromString(storageLocationId);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("destinationLocationId must be a valid UUID", ex);
        }

        return extStorageLocationReplicaRepository
                .findById(parsedId)
                .map(this::toValidation)
                .orElseGet(() -> notFound(parsedId));
    }

    private StorageLocationValidation toValidation(@NonNull ExtStorageLocationReplica replica) {
        StorageLocationValidation validation = new StorageLocationValidation();
        validation.setStorageLocationId(replica.getStorageLocationId());
        validation.setSiteId(replica.getSiteId());
        validation.setExists(true);
        validation.setActive(STATUS_ACTIVE.equalsIgnoreCase(replica.getStatus()));
        validation.setMaxUnitCapacity(replica.getMaxUnitCapacity());
        return validation;
    }

    private StorageLocationValidation notFound(@NonNull UUID storageLocationId) {
        StorageLocationValidation validation = new StorageLocationValidation();
        validation.setStorageLocationId(storageLocationId);
        validation.setExists(false);
        validation.setActive(false);
        return validation;
    }

    /** Validation verdict shape, unchanged from the retired client's contract. */
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
