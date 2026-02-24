package com.positivity.inventory.internal.dto.putaway;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PutawayTaskResponse {

    private String taskId;
    private String sourceReceiptId;
    private String productId;
    private Integer quantity;
    private String sourceLocationId;
    private String suggestedDestinationLocationId;
    private String originalSuggestedLocationId;
    private String finalSuggestedLocationId;
    private String actualDestinationLocationId;
    private String fallbackReason;
    private String status;
    private String assigneeId;
    private Instant createdAt;
    private Instant updatedAt;
}
