package com.positivity.accounting.internal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.positivity.accounting.internal.dto.TaxLiabilityReconciliation;
import com.positivity.accounting.internal.dto.TaxLiabilityReport;
import com.positivity.accounting.internal.dto.TaxLiabilityRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TaxLiabilityCsvRenderer}: deterministic column order,
 * plain-string figures, CSV escaping, and exclusion of the volatile generatedAt
 * timestamp.
 */
class TaxLiabilityCsvRendererTest {

    private final TaxLiabilityCsvRenderer renderer = new TaxLiabilityCsvRenderer();

    @Test
    @DisplayName("Renders metadata, jurisdiction rows, totals, and reconciliation in fixed order")
    void rendersDeterministicCsv() {
        TaxLiabilityReport report = report();

        String expected = """
                Report,Start Date,End Date
                TAX_LIABILITY,2026-06-01,2026-06-30

                Jurisdiction Type,Jurisdiction Code,Jurisdiction Name,Taxable Base,Exempt Base,Exemption Reasons,Tax Collected Gross,Credits Netted,Net Tax
                STATE,WA,Washington,3000.00,500.00,RESALE|GOVT,195.00,13.00,182.00
                CITY,SEATTLE,"Seattle, WA",1000.00,0.00,,25.00,0.00,25.00
                TOTAL,,,4000.00,500.00,,220.00,13.00,207.00

                Tax Payable Account,GL Net Activity,Report Net Tax,Unattributed Credits,Drift,Reconciled
                2200,207.00,207.00,0.00,0.00,true
                """;
        assertEquals(expected, renderer.render(report));
    }

    @Test
    @DisplayName("Identical report data renders byte-identical CSV regardless of generatedAt")
    void generatedAtDoesNotAffectOutput() {
        TaxLiabilityReport first = report();
        TaxLiabilityReport second = report();
        second.setGeneratedAt(Instant.parse("2030-01-01T00:00:00Z"));

        assertEquals(renderer.render(first), renderer.render(second));
    }

    @Test
    @DisplayName("Fields containing CR, LF, or quotes are quoted and quote-escaped")
    void escapesCarriageReturnsLineFeedsAndQuotes() {
        TaxLiabilityReport report = report();
        report.getRows().getFirst().setJurisdictionName("Wash\r\nington \"Evergreen\"");

        String csv = renderer.render(report);

        assertTrue(
                csv.contains("STATE,WA,\"Wash\r\nington \"\"Evergreen\"\"\",3000.00"),
                "CR/LF and quotes must be contained inside a quoted, quote-escaped field");
    }

    private static TaxLiabilityReport report() {
        return TaxLiabilityReport.builder()
                .startDate(LocalDate.of(2026, 6, 1))
                .endDate(LocalDate.of(2026, 6, 30))
                .generatedAt(Instant.parse("2026-06-30T08:00:00Z"))
                .rows(List.of(
                        TaxLiabilityRow.builder()
                                .jurisdictionType("STATE")
                                .jurisdictionCode("WA")
                                .jurisdictionName("Washington")
                                .taxableBase(new BigDecimal("3000.00"))
                                .exemptBase(new BigDecimal("500.00"))
                                .exemptionReasons(List.of("RESALE", "GOVT"))
                                .taxCollectedGross(new BigDecimal("195.00"))
                                .creditsNetted(new BigDecimal("13.00"))
                                .netTax(new BigDecimal("182.00"))
                                .build(),
                        TaxLiabilityRow.builder()
                                .jurisdictionType("CITY")
                                .jurisdictionCode("SEATTLE")
                                .jurisdictionName("Seattle, WA")
                                .taxableBase(new BigDecimal("1000.00"))
                                .exemptBase(new BigDecimal("0.00"))
                                .exemptionReasons(List.of())
                                .taxCollectedGross(new BigDecimal("25.00"))
                                .creditsNetted(new BigDecimal("0.00"))
                                .netTax(new BigDecimal("25.00"))
                                .build()))
                .totalTaxableBase(new BigDecimal("4000.00"))
                .totalExemptBase(new BigDecimal("500.00"))
                .totalTaxCollectedGross(new BigDecimal("220.00"))
                .totalCreditsNetted(new BigDecimal("13.00"))
                .totalNetTax(new BigDecimal("207.00"))
                .reconciliation(TaxLiabilityReconciliation.builder()
                        .taxPayableAccountCode("2200")
                        .glNetActivity(new BigDecimal("207.00"))
                        .reportNetTax(new BigDecimal("207.00"))
                        .unattributedCredits(new BigDecimal("0.00"))
                        .drift(new BigDecimal("0.00"))
                        .reconciled(true)
                        .build())
                .build();
    }
}
