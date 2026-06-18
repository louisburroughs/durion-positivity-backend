package com.positivity.inventory.internal.dto.receiving;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Request to cross-dock received stock directly against a workorder line")
public class CrossDockRequest {

    @Schema(
            description = "Identifier of the workorder the received stock is cross-docked to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotBlank(message = "workorderId is required")
    private String workorderId;

    @Schema(
            description = "Identifier of the workorder line the received stock fulfils",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotBlank(message = "workorderLineId is required")
    private String workorderLineId;

    @Schema(
            description = "Quantity of stock to cross-dock against the workorder line",
            example = "12",
            requiredMode = REQUIRED)
    @NotNull(message = "quantity is required")
    @DecimalMin(value = "0.01", message = "quantity must be positive")
    private BigDecimal quantity;

    @Schema(
            description = "Optional free-text note describing the cross-dock action",
            example = "Cross-docked at dock door 3 for urgent workorder",
            requiredMode = NOT_REQUIRED)
    private String notes;
}
