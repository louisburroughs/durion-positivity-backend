package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;
import org.jspecify.annotations.NonNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Income Statement (Profit & Loss) report response.
 * 
 * Structure follows US GAAP:
 * Revenue → Cost of Goods Sold → Gross Profit →
 * Operating Expenses → Operating Income → Net Income
 * 
 * All amounts from POSTED journal entries only.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncomeStatementReport {

    /**
     * Report period start date (inclusive).
     */
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    /**
     * Report period end date (inclusive).
     */
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    /**
     * Timestamp when report was generated.
     */
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    private Instant generatedAt;

    /**
     * Map of statement line codes to amounts.
     * Example keys: "PL_REVENUE_SALES", "PL_EXPENSE_SALARIES", etc.
     */
    @NonNull
    private Map<String, BigDecimal> lineItems;

    /**
     * Total revenue (sum of all revenue accounts).
     */
    @NonNull
    private BigDecimal totalRevenue;

    /**
     * Total expenses (sum of all expense accounts).
     */
    @NonNull
    private BigDecimal totalExpenses;

    /**
     * Net income (total revenue - total expenses).
     * Positive = profit, Negative = loss.
     */
    @NonNull
    private BigDecimal netIncome;
}
