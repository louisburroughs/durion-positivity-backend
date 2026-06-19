package com.positivity.price.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A single pricing adjustment applied to an estimate")
public class PricingAdjustment {

    @Schema(description = "Adjustment type", example = "PROMOTION", requiredMode = REQUIRED)
    @NotNull
    private String type;

    @Schema(
            description = "Source identifier that produced this adjustment",
            example = "f51d2c5b-a1f2-4f4e-a7cf-4e7b1752e6aa",
            requiredMode = NOT_REQUIRED)
    private UUID sourceId;

    @Schema(description = "Human-readable adjustment label", example = "Summer Labor Discount", requiredMode = REQUIRED)
    @NotNull
    private String label;

    @Schema(
            description = "Adjustment amount; negative values reduce total",
            example = "-100.00",
            requiredMode = REQUIRED)
    @NotNull
    private BigDecimal amount;

    @Schema(description = "Optional adjustment metadata bag", requiredMode = NOT_REQUIRED)
    private Object metadata;
}
