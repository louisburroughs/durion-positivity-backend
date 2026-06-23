package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.LaborOverheadCostReport;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Read-only CAP-316 Labor &amp; Overhead Cost Report generation.
 *
 * <p>Derives the canonical cost-line matrix (monthly + YTD) for a location and fiscal year from posted
 * GL data. Performs no posting, mutation, or inference.
 */
public interface LaborOverheadReportService {

    /**
     * Generate the Labor &amp; Overhead report for one location and fiscal year.
     *
     * @param locationId accounting {@code locationId} dimension value (required)
     * @param fiscalYear fiscal year
     * @param asOfMonth highest elapsed month bounding YTD (1-12); {@code null} defaults to December
     * @return the full canonical report, with {@code 0} amounts for unmapped/zero-activity lines
     */
    @NonNull
    LaborOverheadCostReport generate(@NonNull String locationId, int fiscalYear, @Nullable Integer asOfMonth);
}
