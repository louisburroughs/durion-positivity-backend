package com.positivity.inventory.internal.dto.reallocation;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReallocateResponse {

    private UUID stockItemId;

    private int totalReallocated;

    private int auditRecordsCreated;

    private int atpAfterReallocation;
}
