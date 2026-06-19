package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Request to consume parts on a workorder.
 *
 * CAP:005 Story #158 - Parts Usage Tracking
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request payload to consume authorized part quantity on a workorder")
public class ConsumePartRequest {

    @NotNull(message = "Workorder part ID is required")
    @Schema(
            description = "Workorder part identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID workorderPartId;

    @NotNull(message = "Quantity is required")
    @DecimalMin(value = "0.0001", inclusive = true, message = "Quantity must be positive")
    @Schema(description = "Quantity to consume", example = "2", requiredMode = REQUIRED)
    private BigDecimal quantity;

    @Nullable
    @Schema(description = "Optional usage notes", example = "Installed on front axle", requiredMode = NOT_REQUIRED)
    private String notes;
}
