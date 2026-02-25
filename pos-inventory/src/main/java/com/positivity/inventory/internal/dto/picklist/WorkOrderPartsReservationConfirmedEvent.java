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
public class WorkOrderPartsReservationConfirmedEvent {

    private UUID workorderId;
    private Instant scheduledStartAt;
    private int basePriority;
    private List<ReservationLine> reservationLines;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReservationLine {
        private UUID workorderLineId;
        private UUID reservationId;
        private String sku;
        private int quantity;
    }
}