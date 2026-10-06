package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.AccountType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

/**
 * Drilldown response showing a GL account that contributes to a statement line.
 *
 * Each response represents one account's contribution, so the rows add up to the line they expand.
 * The service returns one for every account on the line: the accounts mapped to a named line, and
 * the accounts a computed line ({@code BS_OTHER_ASSETS}, {@code IS_OTHER_INCOME}, …) collected
 * (CAP:550 S35, #2524). A balance-sheet line reports as-of balances at the end date; an
 * income-statement line reports period movement.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "GL account contribution to a financial statement line")
public class AccountDrilldownResponse {

    /**
     * GL Account ID.
     */
    @Schema(
            description = "Identifier of the GL account",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NonNull
    private String accountId;

    /**
     * Chart-of-accounts code of the GL account.
     */
    @Schema(description = "Chart-of-accounts code of the GL account", example = "4000", requiredMode = REQUIRED)
    @NonNull
    private String accountCode;

    /**
     * The GL account's own name (e.g., "Service Revenue").
     */
    @Schema(description = "Name of the GL account", example = "Service Revenue", requiredMode = REQUIRED)
    @NonNull
    private String accountName;

    /**
     * Type of the GL account.
     */
    @Schema(description = "Type of the GL account", example = "REVENUE", requiredMode = NOT_REQUIRED)
    private AccountType accountType;

    /**
     * The amount this account contributes to the line: the as-of balance at the end date for a
     * balance-sheet line, the period movement for an income-statement line, on the account's normal
     * side and after the mapping's operation, so the rows of a line add up to it.
     */
    @Schema(
            description = "The amount this account contributes to the line (as-of balance at endDate for a"
                    + " balance-sheet line, period movement for an income-statement line); rows add up to the line",
            example = "1250.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal balance;

    /**
     * Statement line code this account contributes to.
     */
    @Schema(
            description = "Statement line code this account contributes to",
            example = "1200-100",
            requiredMode = REQUIRED)
    @NonNull
    private String statementLineCode;
}
