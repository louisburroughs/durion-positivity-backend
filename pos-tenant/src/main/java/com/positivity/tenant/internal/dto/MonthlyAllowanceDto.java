package com.positivity.tenant.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** An amount with its currency (ADR-0067 PC-3): a reading tier's monthly allowance (#2619). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A monthly allowance: amount and ISO 4217 currency")
public class MonthlyAllowanceDto {

    @Schema(
            description = "Amount per calendar month, at least zero, four decimals at most",
            example = "20.00",
            requiredMode = REQUIRED)
    @NotNull
    @DecimalMin("0.0000")
    @Digits(integer = 15, fraction = 4)
    private BigDecimal amount;

    @Schema(
            description = "ISO 4217 code; USD for now (the platform's reading bill is in USD)",
            example = "USD",
            requiredMode = REQUIRED)
    @NotBlank
    @Pattern(regexp = "^[A-Z]{3}$")
    private String currencyCode;
}
