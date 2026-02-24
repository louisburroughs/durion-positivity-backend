package com.positivity.inventory.internal.dto.putaway;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PutawayExecutionResponse {

    private String ledgerEntryId;
    private String taskId;
    private String skuId;
    private String sourceLocationId;
    private String destinationLocationId;
    private int quantityMoved;
    private String transactionType;
    private String status;
    private String executedAt;
    private String actorId;
}
