package com.positivity.accounting.internal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.positivity.accounting.internal.dto.IncomeStatementReport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IncomeStatementCsvRenderer}: deterministic column order,
 * line items in report order, plain-string figures, CSV escaping, and exclusion
 * of the volatile generatedAt timestamp.
 */
class IncomeStatementCsvRendererTest {

    private final IncomeStatementCsvRenderer renderer = new IncomeStatementCsvRenderer();

    @Test
    @DisplayName("Renders metadata, line items in report order, and totals in fixed order")
    void rendersDeterministicCsv() {
        String expected = """
                Report,Start Date,End Date
                INCOME_STATEMENT,2026-01-01,2026-03-31

                Line Item,Amount
                REVENUE_SALES,1250.00
                EXPENSE_COGS,400.00
                TOTAL_REVENUE,1250.00
                TOTAL_EXPENSES,400.00
                NET_INCOME,850.00
                """;
        assertEquals(expected, renderer.render(report()));
    }

    @Test
    @DisplayName("Identical report data renders byte-identical CSV regardless of generatedAt")
    void generatedAtDoesNotAffectOutput() {
        IncomeStatementReport first = report();
        IncomeStatementReport second = report();
        second.setGeneratedAt(Instant.parse("2030-01-01T00:00:00Z"));

        assertEquals(renderer.render(first), renderer.render(second));
    }

    @Test
    @DisplayName("Line codes containing commas or quotes are quoted and quote-escaped")
    void escapesLineCodes() {
        IncomeStatementReport report = report();
        Map<String, BigDecimal> lineItems = new LinkedHashMap<>();
        lineItems.put("REVENUE \"NET\", ADJUSTED", new BigDecimal("1250.00"));
        report.setLineItems(lineItems);

        String csv = renderer.render(report);

        assertTrue(
                csv.contains("\"REVENUE \"\"NET\"\", ADJUSTED\",1250.00"),
                "commas and quotes must be contained inside a quoted, quote-escaped field");
    }

    @Test
    @DisplayName("Line codes starting with =, +, or @ are neutralized; negative amounts stay plain")
    void neutralizesFormulaLeadingLineCodes() {
        IncomeStatementReport report = report();
        Map<String, BigDecimal> lineItems = new LinkedHashMap<>();
        lineItems.put("=HYPERLINK(\"http://evil\",\"click\")", new BigDecimal("1.00"));
        lineItems.put("+REVENUE", new BigDecimal("2.00"));
        lineItems.put("@ADJUSTMENT", new BigDecimal("3.00"));
        report.setLineItems(lineItems);
        report.setNetIncome(new BigDecimal("-123.45"));

        String csv = renderer.render(report);

        assertTrue(csv.contains("\"'=HYPERLINK(\"\"http://evil\"\",\"\"click\"\")\",1.00"));
        assertTrue(csv.contains("'+REVENUE,2.00"));
        assertTrue(csv.contains("'@ADJUSTMENT,3.00"));
        assertTrue(csv.contains("NET_INCOME,-123.45"), "negative figures must stay plain, not neutralized");
    }

    private static IncomeStatementReport report() {
        Map<String, BigDecimal> lineItems = new LinkedHashMap<>();
        lineItems.put("REVENUE_SALES", new BigDecimal("1250.00"));
        lineItems.put("EXPENSE_COGS", new BigDecimal("400.00"));
        return IncomeStatementReport.builder()
                .startDate(LocalDate.of(2026, 1, 1))
                .endDate(LocalDate.of(2026, 3, 31))
                .generatedAt(Instant.parse("2026-06-30T08:00:00Z"))
                .lineItems(lineItems)
                .totalRevenue(new BigDecimal("1250.00"))
                .totalExpenses(new BigDecimal("400.00"))
                .netIncome(new BigDecimal("850.00"))
                .build();
    }
}
