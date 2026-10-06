package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.NormalSide;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for GL Account balance. {@code balance} is debits minus credits; {@code normalBalance}
 * is the same figure on the account's normal side, positive when the account holds its usual
 * balance (CAP:550 S35, #2524).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Balance of a GL account as of a point in time")
public class GLAccountBalanceResponse {

    @Schema(
            description = "Unique identifier of the GL account",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID glAccountId;

    @Schema(description = "Account code of the GL account", example = "4000", requiredMode = NOT_REQUIRED)
    private String accountCode;

    @Schema(description = "Account name of the GL account", example = "Service Revenue", requiredMode = NOT_REQUIRED)
    private String accountName;

    @Schema(description = "Type of the GL account", example = "REVENUE", requiredMode = REQUIRED)
    private AccountType accountType;

    @Schema(
            description = "Normal side of the account: DEBIT for assets and expenses, CREDIT for liabilities,"
                    + " equity and revenue",
            example = "CREDIT",
            requiredMode = REQUIRED)
    private NormalSide normalSide;

    @Schema(description = "Balance of the account, debits minus credits", example = "-1250.00", requiredMode = REQUIRED)
    private BigDecimal balance;

    @Schema(
            description = "Balance on the account's normal side (positive when the account holds its usual balance)",
            example = "1250.00",
            requiredMode = REQUIRED)
    private BigDecimal normalBalance;

    @Schema(
            description = "Point in time the balance is computed as of (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = REQUIRED)
    private Instant asOfDate;
}
