package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Unit tests for the canonical serialization + hashing behind the tax-liability freeze
 * (issue #998): the hash must be stable across wall-clock and BigDecimal-scale noise and must
 * change whenever a frozen figure changes.
 */
@DisplayName("TaxLiabilityCanonicalizer")
class TaxLiabilityCanonicalizerTest {

    @Test
    @DisplayName("Hash ignores generatedAt and BigDecimal scale differences")
    void hashStableAcrossNoiseDimensions() {
        TaxLiabilityReport a = report("100.00", "0.00", Instant.parse("2026-07-01T00:00:00Z"));
        TaxLiabilityReport b = report("100.0000", "0", Instant.parse("2026-07-22T12:34:56Z"));

        assertThat(TaxLiabilityCanonicalizer.canonicalize(a)).isEqualTo(TaxLiabilityCanonicalizer.canonicalize(b));
        assertThat(TaxLiabilityCanonicalizer.contentHash(a))
                .isEqualTo(TaxLiabilityCanonicalizer.contentHash(b))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("Hash changes when any frozen figure changes")
    void hashSensitiveToContent() {
        TaxLiabilityReport base = report("100.00", "0.00", Instant.parse("2026-07-01T00:00:00Z"));
        TaxLiabilityReport differentTax = report("100.01", "0.00", Instant.parse("2026-07-01T00:00:00Z"));
        TaxLiabilityReport differentDrift = report("100.00", "5.00", Instant.parse("2026-07-01T00:00:00Z"));

        assertThat(TaxLiabilityCanonicalizer.contentHash(base))
                .isNotEqualTo(TaxLiabilityCanonicalizer.contentHash(differentTax))
                .isNotEqualTo(TaxLiabilityCanonicalizer.contentHash(differentDrift));
    }

    @Test
    @DisplayName("Reasons join is empty-safe and order-preserving")
    void reasonsJoin() {
        assertThat(TaxLiabilityCanonicalizer.joinReasons(null)).isEmpty();
        assertThat(TaxLiabilityCanonicalizer.joinReasons(List.of())).isEmpty();
        assertThat(TaxLiabilityCanonicalizer.joinReasons(List.of("GOVERNMENT", "RESALE")))
                .isEqualTo("GOVERNMENT,RESALE");
    }

    @Test
    @DisplayName("Separator characters in reason codes cannot forge a colliding canonical form")
    void separatorInjectionDoesNotCollide() {
        TaxLiabilityReport merged = reportWithReasons(List.of("A,B"));
        TaxLiabilityReport distinct = reportWithReasons(List.of("A", "B"));

        assertThat(TaxLiabilityCanonicalizer.canonicalize(merged))
                .isNotEqualTo(TaxLiabilityCanonicalizer.canonicalize(distinct));
        assertThat(TaxLiabilityCanonicalizer.contentHash(merged))
                .isNotEqualTo(TaxLiabilityCanonicalizer.contentHash(distinct));
    }

    @Test
    @DisplayName("Reasons join/split round-trips codes containing every separator character")
    void reasonsRoundTripWithSeparators() {
        List<String> nasty = List.of("A,B", "C|D", "E\\F", "G\nH", "PLAIN");

        assertThat(TaxLiabilityCanonicalizer.splitReasons(TaxLiabilityCanonicalizer.joinReasons(nasty)))
                .isEqualTo(nasty);
        assertThat(TaxLiabilityCanonicalizer.splitReasons("")).isEmpty();
    }

    @Test
    @DisplayName("Hash is sensitive to row order")
    void hashSensitiveToRowOrder() {
        TaxLiabilityRow wa = row("STATE", "WA");
        TaxLiabilityRow king = row("COUNTY", "KING");

        assertThat(TaxLiabilityCanonicalizer.contentHash(reportWithRows(List.of(wa, king))))
                .isNotEqualTo(TaxLiabilityCanonicalizer.contentHash(reportWithRows(List.of(king, wa))));
    }

    private static TaxLiabilityRow row(String type, String code) {
        return TaxLiabilityRow.builder()
                .jurisdictionType(type)
                .jurisdictionCode(code)
                .taxableBase(new BigDecimal("1000.00"))
                .exemptBase(BigDecimal.ZERO)
                .exemptionReasons(List.of())
                .taxCollectedGross(new BigDecimal("65.00"))
                .creditsNetted(BigDecimal.ZERO)
                .netTax(new BigDecimal("65.00"))
                .build();
    }

    private static TaxLiabilityReport reportWithReasons(List<String> reasons) {
        TaxLiabilityRow row = TaxLiabilityRow.builder()
                .jurisdictionType("STATE")
                .jurisdictionCode("WA")
                .taxableBase(new BigDecimal("1000.00"))
                .exemptBase(new BigDecimal("500.00"))
                .exemptionReasons(reasons)
                .taxCollectedGross(new BigDecimal("65.00"))
                .creditsNetted(BigDecimal.ZERO)
                .netTax(new BigDecimal("65.00"))
                .build();
        return reportWithRows(List.of(row));
    }

    private static TaxLiabilityReport reportWithRows(List<TaxLiabilityRow> rows) {
        return TaxLiabilityReport.builder()
                .startDate(LocalDate.of(2026, 6, 1))
                .endDate(LocalDate.of(2026, 6, 30))
                .generatedAt(Instant.parse("2026-07-01T00:00:00Z"))
                .rows(rows)
                .totalTaxableBase(new BigDecimal("1000.00"))
                .totalExemptBase(new BigDecimal("500.00"))
                .totalTaxCollectedGross(new BigDecimal("65.00"))
                .totalCreditsNetted(BigDecimal.ZERO)
                .totalNetTax(new BigDecimal("65.00"))
                .reconciliation(TaxLiabilityReconciliation.builder()
                        .taxPayableAccountCode("2200")
                        .glNetActivity(new BigDecimal("65.00"))
                        .reportNetTax(new BigDecimal("65.00"))
                        .unattributedCredits(BigDecimal.ZERO)
                        .drift(BigDecimal.ZERO)
                        .reconciled(true)
                        .build())
                .build();
    }

    private static TaxLiabilityReport report(String taxCollected, String drift, Instant generatedAt) {
        BigDecimal gross = new BigDecimal(taxCollected);
        TaxLiabilityRow row = TaxLiabilityRow.builder()
                .jurisdictionType("STATE")
                .jurisdictionCode("WA")
                .jurisdictionName(null)
                .taxableBase(new BigDecimal("1000.00"))
                .exemptBase(new BigDecimal("500.00"))
                .exemptionReasons(List.of("RESALE"))
                .taxCollectedGross(gross)
                .creditsNetted(BigDecimal.ZERO)
                .netTax(gross)
                .build();
        return TaxLiabilityReport.builder()
                .startDate(LocalDate.of(2026, 6, 1))
                .endDate(LocalDate.of(2026, 6, 30))
                .generatedAt(generatedAt)
                .rows(List.of(row))
                .totalTaxableBase(new BigDecimal("1000.00"))
                .totalExemptBase(new BigDecimal("500.00"))
                .totalTaxCollectedGross(gross)
                .totalCreditsNetted(BigDecimal.ZERO)
                .totalNetTax(gross)
                .reconciliation(TaxLiabilityReconciliation.builder()
                        .taxPayableAccountCode("2200")
                        .glNetActivity(gross.subtract(new BigDecimal(drift)))
                        .reportNetTax(gross)
                        .unattributedCredits(BigDecimal.ZERO)
                        .drift(new BigDecimal(drift))
                        .reconciled(new BigDecimal(drift).abs().compareTo(new BigDecimal("0.01")) <= 0)
                        .build())
                .build();
    }
}
