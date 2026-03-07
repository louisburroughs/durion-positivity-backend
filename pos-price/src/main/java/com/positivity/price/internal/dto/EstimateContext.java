package com.positivity.price.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Estimate context used for applying promotion rules and calculations")
public class EstimateContext {

    @Schema(description = "Estimate identifier", requiredMode = Schema.RequiredMode.REQUIRED, example = "00000000-0000-0000-0000-000000000001")
    @NotNull(message = "estimateId is required")
    private UUID estimateId;

    @Schema(description = "Customer identifier", requiredMode = Schema.RequiredMode.REQUIRED, example = "00000000-0000-0000-0000-000000000002")
    @NotNull(message = "customerId is required")
    private UUID customerId;

    @Schema(description = "Optional vehicle identifier for vehicle-specific eligibility checks", example = "00000000-0000-0000-0000-000000000003", nullable = true)
    @Nullable
    private UUID vehicleId;

    @Schema(description = "Line items included in the estimate", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "lineItems is required")
    @Size(min = 1, message = "lineItems must contain at least one item")
    private List<@Valid LineItemContext> lineItems;

    @Schema(description = "Estimate subtotal before promotions", requiredMode = Schema.RequiredMode.REQUIRED, example = "500.00")
    @NotNull(message = "subtotal is required")
    @DecimalMin(value = "0", message = "subtotal must be zero or greater")
    private BigDecimal subtotal;

    @Schema(description = "Promotion codes already applied to this estimate", nullable = true, example = "[\"SPRING10\"]")
    @Nullable
    private List<@Size(max = 64, message = "applied promo code must not exceed 64 characters") String> appliedPromoCodes;
}
