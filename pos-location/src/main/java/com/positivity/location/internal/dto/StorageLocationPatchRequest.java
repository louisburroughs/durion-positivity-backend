package com.positivity.location.internal.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.positivity.location.internal.enums.StorageLocationStatus;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Patch payload for storage location updates.
 *
 * Issue: CAP-214 #39
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class StorageLocationPatchRequest {

    private String name;
    private String barcode;
    private StorageLocationStatus status;
    private UUID parentStorageLocationId;
    private UUID destinationStorageLocationId;
}
