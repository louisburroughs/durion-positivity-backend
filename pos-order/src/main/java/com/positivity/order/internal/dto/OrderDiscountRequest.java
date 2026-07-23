package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import lombok.Data;

@Data
@Schema(description = "Request payload for applying an order-level discount (pro-rata across lines)")
public class OrderDiscountRequest {

    @Schema(
            description = "Discount flavor",
            example = "PERCENT",
            allowableValues = {"PERCENT", "AMOUNT"},
            requiredMode = REQUIRED)
    @NotBlank
    private String type;

    @Schema(description = "Percent (0-100] or currency amount, per type", example = "10", requiredMode = REQUIRED)
    @NotNull
    @Positive
    private BigDecimal value;

    @Schema(
            description = "Business reason for the discount",
            example = "GOODWILL_ADJUSTMENT",
            requiredMode = NOT_REQUIRED)
    private String reasonCode;
}
