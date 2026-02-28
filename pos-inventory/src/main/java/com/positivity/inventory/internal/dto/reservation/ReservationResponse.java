package com.positivity.inventory.internal.dto.reservation;

import java.util.UUID;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReservationResponse {

    private UUID reservationId;
    private UUID workorderLineId;
    private UUID stockItemId;
    private int requiredQuantity;
    private int allocatedQuantity;
    private String status;
}
