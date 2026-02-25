package com.positivity.inventory.internal.dto.returns;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReturnResponse {

    private UUID returnId;
    private UUID workorderId;
    private String returnReason;
    private int totalItemsReturned;
    private Instant createdAt;
    private List<UUID> ledgerEntryIds;
}
