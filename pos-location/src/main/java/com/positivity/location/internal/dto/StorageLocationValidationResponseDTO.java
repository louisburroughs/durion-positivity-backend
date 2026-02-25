package com.positivity.location.internal.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StorageLocationValidationResponseDTO {
    private UUID storageLocationId;
    private UUID siteId;
    private boolean exists;
    private boolean active;
    private Integer maxUnitCapacity;
}
