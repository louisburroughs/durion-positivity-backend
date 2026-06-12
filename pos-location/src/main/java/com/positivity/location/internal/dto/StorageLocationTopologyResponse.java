package com.positivity.location.internal.dto;

import com.positivity.location.internal.enums.StorageLocationType;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight flat projection of a storage location for topology consumers
 * (e.g. pos-inventory rollup). Includes every status; never paginated.
 *
 * Issue: CAP-214 #655
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StorageLocationTopologyResponse {

    private UUID id;
    private String name;
    private StorageLocationType type;
    private String status;
    private UUID parentStorageLocationId;
}
