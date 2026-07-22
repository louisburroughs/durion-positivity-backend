package com.positivity.accounting.internal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.positivity.accounting.internal.dto.AgedPayablesReport;
import com.positivity.accounting.internal.dto.AgedPayablesRow;
import com.positivity.accounting.internal.dto.AgedReceivablesReport;
import com.positivity.accounting.internal.dto.AgedReceivablesRow;
import com.positivity.accounting.internal.dto.AgingSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AgedReportCsvRenderer}: deterministic bucket columns for
 * both the AR and AP sides, per-party rows in report order, grand-total row,
 * plain-string figures, CSV escaping, and exclusion of the volatile generatedAt
 * timestamp.
 */
class AgedReportCsvRendererTest {

    private static final UUID CUSTOMER_ID = UUID.fromString("d10217f9-3ec6-46b9-9c87-e7066c100c24");
    private static final UUID VENDOR_ID = UUID.fromString("d10217f9-3ec6-46b9-9c87-e7066c100c25");

    private final AgedReportCsvRenderer renderer = new AgedReportCsvRenderer();

    @Test
    @DisplayName("Renders aged receivables with customer headers, bucket columns, and totals in fixed order")
    void rendersAgedReceivablesCsv() {
        String expected = """
                Report,As-Of Date
                AGED_RECEIVABLES,2026-06-30

                Customer ID,Customer Name,Current (0-30),31-60 Days,61-90 Days,90+ Days,Total Outstanding
                d10217f9-3ec6-46b9-9c87-e7066c100c24,Acme Fleet Services,1250.00,500.00,0.00,0.00,1750.00
                TOTAL,,1250.00,500.00,0.00,0.00,1750.00
                """;
        assertEquals(expected, renderer.render(receivables()));
    }

    @Test
    @DisplayName("Renders aged payables with vendor headers, bucket columns, and totals in fixed order")
    void rendersAgedPayablesCsv() {
        String expected = """
                Report,As-Of Date
                AGED_PAYABLES,2026-06-30

                Vendor ID,Vendor Name,Current (0-30),31-60 Days,61-90 Days,90+ Days,Total Outstanding
                d10217f9-3ec6-46b9-9c87-e7066c100c25,Global Parts Supply,3200.00,750.00,0.00,0.00,3950.00
                TOTAL,,3200.00,750.00,0.00,0.00,3950.00
                """;
        assertEquals(expected, renderer.render(payables()));
    }

    @Test
    @DisplayName("Party names containing commas or quotes are quoted and quote-escaped")
    void escapesPartyNames() {
        AgedReceivablesReport report = receivables();
        report.getRows().getFirst().setCustomerName("Acme, Inc. \"Fleet\"");

        String csv = renderer.render(report);

        assertTrue(
                csv.contains(",\"Acme, Inc. \"\"Fleet\"\"\",1250.00"),
                "commas and quotes must be contained inside a quoted, quote-escaped field");
    }

    @Test
    @DisplayName("Identical report data renders byte-identical CSV regardless of generatedAt")
    void generatedAtDoesNotAffectOutput() {
        AgedReceivablesReport first = receivables();
        AgedReceivablesReport second = receivables();
        second.setGeneratedAt(Instant.parse("2030-01-01T00:00:00Z"));

        assertEquals(renderer.render(first), renderer.render(second));
    }

    private static AgedReceivablesReport receivables() {
        return AgedReceivablesReport.builder()
                .asOfDate(LocalDate.of(2026, 6, 30))
                .generatedAt(Instant.parse("2026-06-30T08:00:00Z"))
                .rows(List.of(AgedReceivablesRow.builder()
                        .customerId(CUSTOMER_ID)
                        .customerName("Acme Fleet Services")
                        .current(new BigDecimal("1250.00"))
                        .days31To60(new BigDecimal("500.00"))
                        .days61To90(new BigDecimal("0.00"))
                        .days90Plus(new BigDecimal("0.00"))
                        .totalOutstanding(new BigDecimal("1750.00"))
                        .build()))
                .totals(AgingSummary.builder()
                        .current(new BigDecimal("1250.00"))
                        .days31To60(new BigDecimal("500.00"))
                        .days61To90(new BigDecimal("0.00"))
                        .days90Plus(new BigDecimal("0.00"))
                        .totalOutstanding(new BigDecimal("1750.00"))
                        .build())
                .build();
    }

    private static AgedPayablesReport payables() {
        return AgedPayablesReport.builder()
                .asOfDate(LocalDate.of(2026, 6, 30))
                .generatedAt(Instant.parse("2026-06-30T08:00:00Z"))
                .rows(List.of(AgedPayablesRow.builder()
                        .vendorId(VENDOR_ID)
                        .vendorName("Global Parts Supply")
                        .current(new BigDecimal("3200.00"))
                        .days31To60(new BigDecimal("750.00"))
                        .days61To90(new BigDecimal("0.00"))
                        .days90Plus(new BigDecimal("0.00"))
                        .totalOutstanding(new BigDecimal("3950.00"))
                        .build()))
                .totals(AgingSummary.builder()
                        .current(new BigDecimal("3200.00"))
                        .days31To60(new BigDecimal("750.00"))
                        .days61To90(new BigDecimal("0.00"))
                        .days90Plus(new BigDecimal("0.00"))
                        .totalOutstanding(new BigDecimal("3950.00"))
                        .build())
                .build();
    }
}
