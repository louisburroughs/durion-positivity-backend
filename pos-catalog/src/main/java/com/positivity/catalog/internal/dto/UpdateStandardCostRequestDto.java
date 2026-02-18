package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import lombok.Data;

@Data
@Schema(description = "Request payload to update standard cost")
public class UpdateStandardCostRequestDto {

    @NotNull
    @Positive
    @Schema(description = "New standard cost", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal newCost;

    @NotBlank
    @Schema(description = "Reason code for manual change", requiredMode = Schema.RequiredMode.REQUIRED)
    private String reasonCode;
}
