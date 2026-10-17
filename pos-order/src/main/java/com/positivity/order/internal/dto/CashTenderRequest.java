package com.positivity.order.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import lombok.Data;

/**
 * A cash tender taken at the register (ADR-0067 A8, #2662). The drawer is the order's session; it is not sent.
 */
@Data
@Schema(
        description = "A cash tender at the register: what the customer handed over, optionally the part of the"
                + " balance it settles, and the drawer's currency. pos-order rounds the cash due to the currency's cash"
                + " increment and records the tender through pos-invoice.")
public class CashTenderRequest {

    @NotNull
    @DecimalMin(value = "0.00")
    @Schema(
            description = "Cash the customer handed over, in the drawer's currency at its exponent; never rounded",
            example = "20.00",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal cashTendered;

    @Positive
    @Schema(
            description = "Optional partial cash amount applied to the balance (a split tender). Must be a whole"
                    + " multiple of the currency's cash increment; a partial amount rounds nothing. Omit to settle"
                    + " the whole remaining balance with cash, which is what rounds.",
            example = "10.00",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private BigDecimal cashApplied;

    @NotBlank
    @Schema(
            description = "ISO 4217 code of the cash; must be the drawer's currency",
            example = "CAD",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String currencyCode;
}
