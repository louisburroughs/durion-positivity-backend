package com.positivity.inventory.internal.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryLedgerFilterParams {
    @Nullable
    private String productSku;

    @Nullable
    private UUID locationId;

    @Nullable
    private UUID storageLocationId;

    @Nullable
    private Instant dateFrom;

    @Nullable
    private Instant dateTo;

    @Nullable
    private UUID sourceTransactionId;

    @Nullable
    private UUID workorderId;

    @Nullable
    private UUID workorderLineId;

    @Nullable
    private List<String> movementTypes;

    @Nullable
    private String pageToken;

    @Builder.Default
    private int pageSize = 50;
}
