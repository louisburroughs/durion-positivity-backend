package com.positivity.inventory.dto.reallocation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReallocateResponse {

    private String sku;

    private int totalReallocated;

    private int auditRecordsCreated;

    private int atpAfterReallocation;
}