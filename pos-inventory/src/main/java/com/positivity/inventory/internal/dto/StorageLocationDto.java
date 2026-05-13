package com.positivity.inventory.internal.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StorageLocationDto {
    private UUID storageLocationId;
    private UUID locationId;
    private String code;
    private int capacity;
    private boolean active;
}
