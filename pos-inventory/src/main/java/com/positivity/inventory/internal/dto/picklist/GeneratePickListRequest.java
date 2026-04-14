package com.positivity.inventory.internal.dto.picklist;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GeneratePickListRequest {

    private UUID workorderId;
    private Instant scheduledStartAt;
    private int basePriority;
    private List<PickLineItem> lineItems;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PickLineItem {
        private UUID workorderLineId;
        private UUID reservationId;
        private String sku;
        private int quantity;
    }
}
