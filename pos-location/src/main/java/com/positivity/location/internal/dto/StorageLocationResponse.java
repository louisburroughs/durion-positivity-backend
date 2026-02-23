package com.positivity.location.internal.dto;

import com.positivity.location.internal.enums.StorageLocationType;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response payload for storage location endpoints.
 *
 * Issue: CAP-214 #39
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StorageLocationResponse {

    private UUID id;
    private String name;
    private String barcode;
    private StorageLocationType type;
    private String status;
    private UUID siteId;
    private UUID parentStorageLocationId;
    private int inventoryCount;
}
