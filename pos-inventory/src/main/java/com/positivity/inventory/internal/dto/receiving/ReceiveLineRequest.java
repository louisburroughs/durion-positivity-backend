package com.positivity.inventory.internal.dto.receiving;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Received quantity recorded against a single receiving session line")
public class ReceiveLineRequest {
    @Schema(
            description = "Identifier of the receiving line being recorded",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull(message = "lineId is required")
    private UUID lineId;

    @Schema(
            description = "Quantity actually received for the line; may be zero",
            example = "8",
            requiredMode = REQUIRED)
    @NotNull(message = "receivedQuantity is required")
    @DecimalMin(value = "0.0", inclusive = true, message = "receivedQuantity must be >= 0")
    private BigDecimal receivedQuantity;
}
