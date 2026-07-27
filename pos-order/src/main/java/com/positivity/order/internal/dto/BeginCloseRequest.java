package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import lombok.Data;

@Data
@Schema(description = "Request payload for beginning a register session close")
public class BeginCloseRequest {

    @Schema(description = "Physical cash counted in the drawer", example = "310.00", requiredMode = REQUIRED)
    @NotNull
    @PositiveOrZero
    private BigDecimal countedCash;
}
