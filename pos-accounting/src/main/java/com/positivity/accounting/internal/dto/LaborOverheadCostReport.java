package com.positivity.accounting.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CAP-316 read-only Labor &amp; Overhead Cost Report for one location and fiscal year: the full
 * canonical cost-line hierarchy with monthly + YTD amounts, plus the currency context.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Read-only Labor & Overhead Cost Report (CAP-316) for a location and fiscal year")
public class LaborOverheadCostReport {

    @Schema(
            description = "Location/dealer identifier (accounting locationId dimension)",
            example = "LOC-107",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String locationId;

    @Schema(
            description = "Human-readable location label",
            example = "Smetzer's Wooster",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String locationLabel;

    @Schema(description = "Fiscal year", example = "2026", requiredMode = Schema.RequiredMode.REQUIRED)
    private int fiscalYear;

    @Schema(
            description = "Highest elapsed month bounding YTD (1-12)",
            example = "12",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private int asOfMonth;

    @Schema(
            description = "Currency of the amounts (line 2.11.4 is always USD)",
            example = "USD",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String currency;

    @Schema(
            description = "Local currency units per USD (1.00 for US plants)",
            example = "1.00",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal localCurrencyPerUsd;

    @Schema(
            description = "Average conversion rate used for the year (1.00 for US plants)",
            example = "1.00",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal averageRate;

    @Schema(
            description = "Cost lines in canonical order (Labor §1, Overhead §2, totals)",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private List<LaborOverheadReportLine> lines;
}
