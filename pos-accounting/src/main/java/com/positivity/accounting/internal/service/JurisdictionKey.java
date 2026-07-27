package com.positivity.accounting.internal.service;

import java.util.Locale;
import java.util.Map;

/**
 * Grouping key for a tax jurisdiction: level type + code (story T8, issue #966).
 *
 * <p>Ordering is state → county → city → special, then by code. That order drives the T8
 * report's row order, the {@link TaxCreditAllocator} residual tie-break, and the
 * per-jurisdiction residual correction performed when a credit memo's tax attribution is
 * frozen (issue #996) — so equal-share residuals always land deterministically on the
 * highest jurisdiction level.
 *
 * <p>Extracted to its own type (from {@code FinancialReportingServiceImpl}) so the
 * report-time and credit-creation-time paths group and order jurisdictions identically.
 */
record JurisdictionKey(String type, String code) implements Comparable<JurisdictionKey> {

    private static final Map<String, Integer> JURISDICTION_TYPE_RANK =
            Map.of("STATE", 0, "COUNTY", 1, "CITY", 2, "SPECIAL", 3);

    @Override
    public int compareTo(JurisdictionKey other) {
        int byRank = Integer.compare(rank(type), rank(other.type));
        if (byRank != 0) {
            return byRank;
        }
        int byType = type.compareToIgnoreCase(other.type);
        if (byType != 0) {
            return byType;
        }
        return code.compareTo(other.code);
    }

    private static int rank(String type) {
        return JURISDICTION_TYPE_RANK.getOrDefault(type.toUpperCase(Locale.ROOT), 99);
    }
}
