package com.positivity.accounting.internal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.positivity.accounting.internal.dto.BalanceSheetReport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BalanceSheetCsvRenderer}: deterministic column order,
 * line items in report order, plain-string figures, and exclusion of the
 * volatile generatedAt timestamp.
 */
class BalanceSheetCsvRendererTest {

    private final BalanceSheetCsvRenderer renderer = new BalanceSheetCsvRenderer();

    @Test
    @DisplayName("Renders metadata, line items in report order, totals, and balanced flag in fixed order")
    void rendersDeterministicCsv() {
        String expected = """
                Report,As-Of Date
                BALANCE_SHEET,2026-06-30

                Line Item,Amount
                ASSET_CURRENT_CASH,1250000.00
                LIABILITY_CURRENT_AP,450000.00
                EQUITY_OWNERS,800000.00
                TOTAL_ASSETS,1250000.00
                TOTAL_LIABILITIES,450000.00
                TOTAL_EQUITY,800000.00
                BALANCED,true
                """;
        assertEquals(expected, renderer.render(report()));
    }

    @Test
    @DisplayName("Identical report data renders byte-identical CSV regardless of generatedAt")
    void generatedAtDoesNotAffectOutput() {
        BalanceSheetReport first = report();
        BalanceSheetReport second = report();
        second.setGeneratedAt(Instant.parse("2030-01-01T00:00:00Z"));

        assertEquals(renderer.render(first), renderer.render(second));
    }

    @Test
    @DisplayName("Line codes starting with =, +, or @ are neutralized; negative amounts stay plain")
    void neutralizesFormulaLeadingLineCodes() {
        BalanceSheetReport report = report();
        Map<String, BigDecimal> lineItems = new LinkedHashMap<>();
        lineItems.put("=2+5", new BigDecimal("1.00"));
        lineItems.put("+ASSET", new BigDecimal("2.00"));
        lineItems.put("@EQUITY", new BigDecimal("3.00"));
        report.setLineItems(lineItems);
        report.setTotalEquity(new BigDecimal("-123.45"));

        String csv = renderer.render(report);

        assertTrue(csv.contains("'=2+5,1.00"));
        assertTrue(csv.contains("'+ASSET,2.00"));
        assertTrue(csv.contains("'@EQUITY,3.00"));
        assertTrue(csv.contains("TOTAL_EQUITY,-123.45"), "negative figures must stay plain, not neutralized");
    }

    private static BalanceSheetReport report() {
        Map<String, BigDecimal> lineItems = new LinkedHashMap<>();
        lineItems.put("ASSET_CURRENT_CASH", new BigDecimal("1250000.00"));
        lineItems.put("LIABILITY_CURRENT_AP", new BigDecimal("450000.00"));
        lineItems.put("EQUITY_OWNERS", new BigDecimal("800000.00"));
        return BalanceSheetReport.builder()
                .asOfDate(LocalDate.of(2026, 6, 30))
                .generatedAt(Instant.parse("2026-06-30T08:00:00Z"))
                .lineItems(lineItems)
                .totalAssets(new BigDecimal("1250000.00"))
                .totalLiabilities(new BigDecimal("450000.00"))
                .totalEquity(new BigDecimal("800000.00"))
                .balanced(true)
                .build();
    }
}
