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
public class WorkorderPickedItemResponse {
    private UUID pickTaskId;
    private UUID pickListId;
    private UUID skuId;
    private int qtyPicked;
    private int qtyConsumed;
    private int qtyRemaining;
    private String status;
}
