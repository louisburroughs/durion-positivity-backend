package com.positivity.workorder.internal.dto.pick;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkorderPickTaskResponse {
    private UUID pickTaskId;
    private UUID pickListId;
    private UUID skuId;
    private UUID locationId;
    private int requiredQty;
    private int pickedQty;
    private int remainingQty;
    private String status;
    private int sortOrder;
    private long version;
}
