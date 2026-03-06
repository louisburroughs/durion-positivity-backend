package com.positivity.price.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A single pricing adjustment applied to an estimate")
public class PricingAdjustment {

    @Schema(description = "Adjustment type", example = "PROMOTION")
    private String type;

    @Schema(description = "Source identifier that produced this adjustment", example = "f51d2c5b-a1f2-4f4e-a7cf-4e7b1752e6aa")
    private UUID sourceId;

    @Schema(description = "Human-readable adjustment label", example = "Summer Labor Discount")
    private String label;

    @Schema(description = "Adjustment amount; negative values reduce total", example = "-100.00")
    private BigDecimal amount;

    @Schema(description = "Optional adjustment metadata bag", nullable = true)
    private Object metadata;
}
