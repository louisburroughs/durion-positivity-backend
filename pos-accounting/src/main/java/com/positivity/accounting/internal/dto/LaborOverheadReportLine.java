package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.positivity.accounting.internal.enums.CostType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One cost line of the CAP-316 Labor &amp; Overhead report: canonical metadata plus 12 monthly amounts
 * and the year-to-date total.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A single Labor & Overhead cost line with monthly amounts and YTD")
public class LaborOverheadReportLine {

    @Schema(description = "Canonical line code", example = "1.1.1", requiredMode = Schema.RequiredMode.REQUIRED)
    private String code;

    @Schema(
            description = "Display label",
            example = "Hourly wages and bonuses",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String label;

    @Schema(description = "Parent line code, if any", example = "1.1", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String parentCode;

    @Schema(
            description = "Hierarchy depth (totals = 1, N.M = 2, N.M.K = 3)",
            example = "3",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private int level;

    @Schema(
            description = "Fixed/Variable classification; absent when the source shows none",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private CostType costType;

    @JsonProperty("isSubtotal")
    @Schema(
            description = "True for computed roll-up rows; false for GL-mapped leaf lines",
            example = "false",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean isSubtotal;

    @JsonProperty("usdOnly")
    @Schema(
            description = "True for line 2.11.4, which is reported in USD even on local-currency plants",
            example = "false",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean usdOnly;

    @Schema(
            description = "Include/exclude guidance from the Definitions tab",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String definition;

    @Schema(
            description = "12 monthly amounts in canonical order (index 0 = January)",
            example = "[0,0,0,0,0,0,0,0,0,62645,0,0]",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private List<BigDecimal> monthly;

    @Schema(
            description = "Year-to-date total over elapsed months",
            example = "62645",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal ytd;
}
