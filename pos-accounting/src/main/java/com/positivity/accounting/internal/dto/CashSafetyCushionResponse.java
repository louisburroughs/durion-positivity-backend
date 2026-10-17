package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The tenant's cash safety cushion (CAP:550 S19, #2515). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "The tenant's cash safety cushion; amount is null while it is unset")
public class CashSafetyCushionResponse {

    @Schema(
            description = "The cushion in the functional currency; null when unset (no line, no warning); 0 is a"
                    + " real value",
            example = "2500.00",
            nullable = true,
            requiredMode = REQUIRED)
    private BigDecimal amount;

    @Schema(description = "The tenant's functional currency", example = "USD", requiredMode = REQUIRED)
    private String currencyCode;
}
