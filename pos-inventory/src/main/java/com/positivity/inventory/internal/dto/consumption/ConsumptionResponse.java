package com.positivity.inventory.internal.dto.consumption;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConsumptionResponse {

    private UUID consumptionId;
    private UUID workorderId;
    private UUID pickListId;
    private int totalItemsConsumed;
    private Instant createdAt;
    private List<UUID> ledgerEntryIds;
}
