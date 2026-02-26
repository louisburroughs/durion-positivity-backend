package com.positivity.inventory.internal.dto.receiving;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CrossDockRequest {

    @NotBlank(message = "workorderId is required")
    private String workorderId;

    @NotBlank(message = "workorderLineId is required")
    private String workorderLineId;

    @NotNull(message = "quantity is required")
    @DecimalMin(value = "0.01", message = "quantity must be positive")
    private BigDecimal quantity;

    private String notes;
}
