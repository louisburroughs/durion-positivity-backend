package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
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
public class BalanceSheetReport {

    /**
     * Report as-of date.
     */
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
    private LocalDate asOfDate;

    /**
     * Timestamp when report was generated.
     */
    @NonNull
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    private Instant generatedAt;

    /**
     * Map of statement line codes to amounts.
     * Example keys: "BS_ASSETS_CURRENT_CASH", "BS_LIABILITIES_CURRENT_AP", etc.
     */
    @NonNull
    private Map<String, BigDecimal> lineItems;

    /**
     * Total assets (sum of all asset accounts).
     */
    @NonNull
    private BigDecimal totalAssets;

    /**
     * Total liabilities (sum of all liability accounts).
     */
    @NonNull
    private BigDecimal totalLiabilities;

    /**
     * Total equity (sum of all equity accounts).
     */
    @NonNull
    private BigDecimal totalEquity;

    /**
     * Whether the fundamental accounting equation balances.
     * TRUE if: totalAssets == totalLiabilities + totalEquity (within tolerance)
     */
    @NonNull
    private Boolean balanced;
}
