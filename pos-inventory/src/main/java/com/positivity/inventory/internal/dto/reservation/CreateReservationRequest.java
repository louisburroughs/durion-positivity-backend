package com.positivity.inventory.internal.dto.reservation;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateReservationRequest {

    private UUID workorderLineId;
    private String sku;
    private int requiredQuantity;
}
