package com.positivity.inventory.internal.dto.replenishment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplenishmentTaskResponse {

    private String taskId;
    private String itemSKU;
    private int quantity;
    private String sourceLocationId;
    private String destinationLocationId;
    private String status;
    private String triggerType;
    private String decisionReason;
    private String sourcingReason;
    private String assignedTo;
    private String createdAt;
}
