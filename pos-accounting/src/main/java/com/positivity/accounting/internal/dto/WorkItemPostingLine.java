package com.positivity.accounting.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** One line of the entry an action would post (CAP:550 S19, #2515): account number and name, side and amount. */
@Schema(name = "WorkItemPostingLine", description = "A line of the entry the item's action would post")
public record WorkItemPostingLine(
        @Schema(description = "The account's number", example = "2000", requiredMode = Schema.RequiredMode.REQUIRED)
        String accountCode,

        @Schema(description = "The account's name", example = "Accounts Payable", requiredMode = Schema.RequiredMode.REQUIRED)
        String accountName,

        @Schema(description = "DEBIT or CREDIT", example = "DEBIT", requiredMode = Schema.RequiredMode.REQUIRED)
        Side side,

        @Schema(description = "The amount, positive, in the functional currency", example = "1197.00", requiredMode = Schema.RequiredMode.REQUIRED)
        BigDecimal amount) {

    /** The side of a line. */
    public enum Side {
        DEBIT,
        CREDIT
    }

    /** The line of a signed amount: a debit when positive, a credit when negative. */
    public static WorkItemPostingLine ofSigned(String accountCode, String accountName, BigDecimal signed) {
        return new WorkItemPostingLine(
                accountCode, accountName, signed.signum() >= 0 ? Side.DEBIT : Side.CREDIT, signed.abs());
    }
}
