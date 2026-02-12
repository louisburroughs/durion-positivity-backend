package com.positivity.accounting.internal.dto;

import java.math.BigDecimal;

import org.jspecify.annotations.NonNull;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
public class AccountDrilldownResponse {

    /**
     * GL Account ID.
     */
    @NonNull
    private String accountId;

    /**
     * GL Account name (e.g., "Salaries and Wages").
     */
    @NonNull
    private String accountName;

    /**
     * Account balance for the period.
     * Sum of POSTED journal lines for this account within the date range.
     */
    @NonNull
    private BigDecimal balance;

    /**
     * Statement line code this account contributes to.
     */
    @NonNull
    private String statementLineCode;
}
