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
 * Balance Sheet report response.
 *
 * Structure follows US GAAP:
 * Assets (Current + Non-Current) =
 * Liabilities (Current + Non-Current) + Stockholders' Equity
 *
 * All amounts from POSTED journal entries as of the specified date.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description =
                "Balance Sheet report response with assets, liabilities, and equity totals derived from POSTED journal entries")
public class BalanceSheetReport {

    /**
     * Report as-of date.
     */
    @Schema(description = "Date the balance sheet is reported as of", example = "2026-06-18", requiredMode = REQUIRED)
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate asOfDate;

    /**
     * Timestamp when report was generated.
     */
    @Schema(
            description = "Timestamp when the report was generated (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = REQUIRED)
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    private Instant generatedAt;

    /**
     * Map of statement line codes to amounts.
     * Example keys: "BS_ASSETS_CURRENT_CASH", "BS_LIABILITIES_CURRENT_AP", etc.
     */
    @Schema(
            description = "Map of balance sheet line codes to their amounts (e.g. BS_ASSETS_CURRENT_CASH)",
            requiredMode = REQUIRED)
    @NonNull
    private Map<String, BigDecimal> lineItems;

    /**
     * Total assets (sum of all asset accounts).
     */
    @Schema(description = "Total assets (sum of all asset accounts)", example = "1250000.00", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalAssets;

    /**
     * Total liabilities (sum of all liability accounts).
     */
    @Schema(
            description = "Total liabilities (sum of all liability accounts)",
            example = "450000.00",
            requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalLiabilities;

    /**
     * Total equity (sum of all equity accounts).
     */
    @Schema(description = "Total equity (sum of all equity accounts)", example = "800000.00", requiredMode = REQUIRED)
    @NonNull
    private BigDecimal totalEquity;

    /**
     * Whether the fundamental accounting equation balances.
     * TRUE if: totalAssets == totalLiabilities + totalEquity (within tolerance)
     */
    @Schema(
            description = "Whether the accounting equation balances (assets == liabilities + equity within tolerance)",
            example = "true",
            requiredMode = REQUIRED)
    @NonNull
    private Boolean balanced;
}
