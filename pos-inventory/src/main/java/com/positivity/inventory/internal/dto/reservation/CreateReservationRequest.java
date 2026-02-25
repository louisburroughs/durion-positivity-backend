package com.positivity.inventory.internal.dto.reservation;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateReservationRequest {

    @NotNull
    private UUID workorderLineId;

    @NotNull
    private String sku;

    @Positive
    private int requiredQuantity;
}
