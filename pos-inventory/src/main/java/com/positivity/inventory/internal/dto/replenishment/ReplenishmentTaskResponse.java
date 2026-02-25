package com.positivity.inventory.internal.dto.replenishment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplenishmentTaskResponse {

    private String taskId;
    private String itemSKU;
    private int quantity;
    private UUID sourceLocationId;
    private UUID destinationLocationId;
    private String status;
    private String triggerType;
    private String decisionReason;
    private String sourcingReason;
    private String assignedTo;
    private String createdAt;
}
