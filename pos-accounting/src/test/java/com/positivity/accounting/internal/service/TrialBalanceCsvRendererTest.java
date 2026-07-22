package com.positivity.accounting.internal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.positivity.accounting.internal.dto.EntryNumberGapCheck;
import com.positivity.accounting.internal.dto.TrialBalanceReport;
import com.positivity.accounting.internal.dto.TrialBalanceRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TrialBalanceCsvRenderer}: deterministic column order,
 * per-account rows in report order, debit/credit totals row, gap footnote block,
 * plain-string figures, and exclusion of the volatile generatedAt timestamp.
 */
class TrialBalanceCsvRendererTest {

    private final TrialBalanceCsvRenderer renderer = new TrialBalanceCsvRenderer();

    @Test
    @DisplayName("Renders metadata, account rows, totals, balanced flag, and gap footnote in fixed order")
    void rendersDeterministicCsv() {
        String expected = """
                Report,As-Of Date
                TRIAL_BALANCE,2026-06-30

                Account Number,Account Name,Total Debit,Total Credit,Balance
                1000,Cash - Operating,125000.00,45000.00,80000.00
                2000,Accounts Payable,10000.00,90000.00,-80000.00
                TOTAL,,135000.00,135000.00,
                BALANCED,true

                Sequence Scope,Missing Entry Numbers
                JE-202606,7|12
                """;
        assertEquals(expected, renderer.render(report()));
    }

    @Test
    @DisplayName("Clean ledger renders an empty gap footnote under the header")
    void cleanLedgerRendersEmptyGapBlock() {
        TrialBalanceReport report = report();
        report.setEntryNumberGaps(List.of());

        String csv = renderer.render(report);

        assertEquals("Sequence Scope,Missing Entry Numbers\n", csv.substring(csv.indexOf("Sequence Scope")));
    }

    @Test
    @DisplayName("Identical report data renders byte-identical CSV regardless of generatedAt")
    void generatedAtDoesNotAffectOutput() {
        TrialBalanceReport first = report();
        TrialBalanceReport second = report();
        second.setGeneratedAt(Instant.parse("2030-01-01T00:00:00Z"));

        assertEquals(renderer.render(first), renderer.render(second));
    }

    private static TrialBalanceReport report() {
        return TrialBalanceReport.builder()
                .asOfDate(LocalDate.of(2026, 6, 30))
                .generatedAt(Instant.parse("2026-06-30T08:00:00Z"))
                .rows(List.of(
                        TrialBalanceRow.builder()
                                .accountId("123e4567-e89b-12d3-a456-426614174000")
                                .accountNumber("1000")
                                .accountName("Cash - Operating")
                                .totalDebit(new BigDecimal("125000.00"))
                                .totalCredit(new BigDecimal("45000.00"))
                                .balance(new BigDecimal("80000.00"))
                                .build(),
                        TrialBalanceRow.builder()
                                .accountId("123e4567-e89b-12d3-a456-426614174001")
                                .accountNumber("2000")
                                .accountName("Accounts Payable")
                                .totalDebit(new BigDecimal("10000.00"))
                                .totalCredit(new BigDecimal("90000.00"))
                                .balance(new BigDecimal("-80000.00"))
                                .build()))
                .totalDebit(new BigDecimal("135000.00"))
                .totalCredit(new BigDecimal("135000.00"))
                .balanced(true)
                .entryNumberGaps(List.of(EntryNumberGapCheck.builder()
                        .scopeKey("JE-202606")
                        .missingNumbers(List.of(7L, 12L))
                        .build()))
                .build();
    }
}
