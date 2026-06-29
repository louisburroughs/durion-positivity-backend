package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

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
 * Each response represents one account's contribution.
 * Service returns a list of these for all accounts mapped to a statement line.
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
    @Schema(description = "Identifier of the GL account", example = "4000", requiredMode = REQUIRED)
    @NonNull
    private String accountId;

    /**
     * GL Account name (e.g., "Salaries and Wages").
     */
    @Schema(description = "Name of the GL account", example = "Salaries and Wages", requiredMode = REQUIRED)
    @NonNull
    private String accountName;

    /**
     * Account balance for the period.
     * Sum of POSTED journal lines for this account within the date range.
     */
    @Schema(
            description = "Account balance for the period (sum of POSTED journal lines within the date range)",
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
