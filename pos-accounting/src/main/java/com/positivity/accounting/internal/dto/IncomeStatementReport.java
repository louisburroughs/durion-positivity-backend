package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import lombok.*;
import org.jspecify.annotations.NonNull;

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
@Schema(description = "Income statement (Profit & Loss) report built from POSTED journal entries")
public class IncomeStatementReport {

    /**
     * Report period start date (inclusive).
     */
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @Schema(description = "Report period start date (inclusive)", example = "2026-01-01", requiredMode = REQUIRED)
    private LocalDate startDate;

    /**
     * Report period end date (inclusive).
     */
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    @Schema(description = "Report period end date (inclusive)", example = "2026-03-31", requiredMode = REQUIRED)
    private LocalDate endDate;

    /**
     * Timestamp when report was generated.
     */
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    @Schema(description = "Timestamp when the report was generated", example = "2026-06-18T08:00:00Z", requiredMode = REQUIRED)
    private Instant generatedAt;

    /**
     * Map of statement line codes to amounts.
     * Example keys: "PL_REVENUE_SALES", "PL_EXPENSE_SALARIES", etc.
     */
    @NonNull
    @Schema(
            description = "Map of statement line codes to amounts (e.g. PL_REVENUE_SALES, PL_EXPENSE_SALARIES)",
            example = "{\"PL_REVENUE_SALES\":\"1250.00\"}",
            requiredMode = REQUIRED)
    private Map<String, BigDecimal> lineItems;

    /**
     * Total revenue (sum of all revenue accounts).
     */
    @NonNull
    @Schema(description = "Total revenue (sum of all revenue accounts)", example = "1250.00", requiredMode = REQUIRED)
    private BigDecimal totalRevenue;

    /**
     * Total expenses (sum of all expense accounts).
     */
    @NonNull
    @Schema(description = "Total expenses (sum of all expense accounts)", example = "1250.00", requiredMode = REQUIRED)
    private BigDecimal totalExpenses;

    /**
     * Net income (total revenue - total expenses).
     * Positive = profit, Negative = loss.
     */
    @NonNull
    @Schema(
            description = "Net income (total revenue minus total expenses); positive is profit, negative is loss",
            example = "1250.00",
            requiredMode = REQUIRED)
    private BigDecimal netIncome;
}
