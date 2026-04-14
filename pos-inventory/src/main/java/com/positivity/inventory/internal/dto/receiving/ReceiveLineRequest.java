package com.positivity.inventory.internal.dto.receiving;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReceiveLineRequest {
    @NotNull(message = "lineId is required")
    private UUID lineId;

    @NotNull(message = "receivedQuantity is required")
    @DecimalMin(value = "0.0", inclusive = true, message = "receivedQuantity must be >= 0")
    private BigDecimal receivedQuantity;
}
