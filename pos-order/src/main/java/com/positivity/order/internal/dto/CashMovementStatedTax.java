package com.positivity.order.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * One regime's tax as stated on a petty-expense receipt (CAP:550 S32d item 5), in a request and in a response.
 *
 * @param regime the regime code from pos-tax's country profile
 * @param amount the stated amount, positive, in the drawer's currency
 */
@Schema(
        description =
                "One indirect-tax regime's amount as stated on a petty-expense receipt, copied and never computed")
public record CashMovementStatedTax(
        @Schema(
                description = "Regime code, 1 to 32 upper-case letters, digits or underscores; it must be one the"
                        + " category's offeredRegimes lists",
                example = "REGIME_1",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String regime,

        @Schema(
                description = "Amount stated, above zero, below the movement's amount and at most the currency's"
                        + " decimals; the register omits a blank or unknown figure",
                example = "4.60",
                requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal amount) {}
