package com.positivity.accounting.internal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.positivity.accounting.internal.dto.GeneralLedgerAccountSection;
import com.positivity.accounting.internal.dto.GeneralLedgerLine;
import com.positivity.accounting.internal.dto.GeneralLedgerReport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GeneralLedgerCsvRenderer}: deterministic column order,
 * per-account sections with opening/closing markers and running balances, grand
 * totals, plain-string figures, and exclusion of the volatile generatedAt
 * timestamp.
 */
class GeneralLedgerCsvRendererTest {

    private final GeneralLedgerCsvRenderer renderer = new GeneralLedgerCsvRenderer();

    @Test
    @DisplayName("Renders metadata, section marker rows, lines with running balance, and grand totals in fixed order")
    void rendersDeterministicCsv() {
        String expected = """
                Report,Start Date,End Date,Account Filter
                GENERAL_LEDGER,2026-06-01,2026-06-30,ALL

                Account Number,Account Name,Entry Number,Transaction Date,Description,Debit,Credit,Running Balance
                1000,Cash - Operating,OPENING BALANCE,,,,,50000.00
                1000,Cash - Operating,JE-202606-1,2026-06-05,Customer payment,30000.00,,80000.00
                1000,Cash - Operating,JE-202606-2,2026-06-20,Rent,,5000.00,75000.00
                1000,Cash - Operating,ACCOUNT TOTAL,,,30000.00,5000.00,75000.00
                GRAND TOTAL,,,,,30000.00,5000.00,
                """;
        assertEquals(expected, renderer.render(report(null)));
    }

    @Test
    @DisplayName("Account filter echoes in the metadata block when present")
    void rendersAccountFilter() {
        String accountId = "123e4567-e89b-12d3-a456-426614174000";

        String csv = renderer.render(report(accountId));

        assertTrue(csv.contains("GENERAL_LEDGER,2026-06-01,2026-06-30," + accountId));
    }

    @Test
    @DisplayName("Identical report data renders byte-identical CSV regardless of generatedAt")
    void generatedAtDoesNotAffectOutput() {
        GeneralLedgerReport first = report(null);
        GeneralLedgerReport second = report(null);
        second.setGeneratedAt(Instant.parse("2030-01-01T00:00:00Z"));

        assertEquals(renderer.render(first), renderer.render(second));
    }

    private static GeneralLedgerReport report(String accountId) {
        return GeneralLedgerReport.builder()
                .accountId(accountId)
                .startDate(LocalDate.of(2026, 6, 1))
                .endDate(LocalDate.of(2026, 6, 30))
                .generatedAt(Instant.parse("2026-06-30T08:00:00Z"))
                .accounts(List.of(GeneralLedgerAccountSection.builder()
                        .accountId("123e4567-e89b-12d3-a456-426614174000")
                        .accountNumber("1000")
                        .accountName("Cash - Operating")
                        .openingBalance(new BigDecimal("50000.00"))
                        .lines(List.of(
                                GeneralLedgerLine.builder()
                                        .journalEntryId(UUID.fromString("01960003-0000-7000-8000-000000000001"))
                                        .entryNumber("JE-202606-1")
                                        .transactionDate(LocalDate.of(2026, 6, 5))
                                        .description("Customer payment")
                                        .debitAmount(new BigDecimal("30000.00"))
                                        .runningBalance(new BigDecimal("80000.00"))
                                        .build(),
                                GeneralLedgerLine.builder()
                                        .journalEntryId(UUID.fromString("01960003-0000-7000-8000-000000000002"))
                                        .entryNumber("JE-202606-2")
                                        .transactionDate(LocalDate.of(2026, 6, 20))
                                        .description("Rent")
                                        .creditAmount(new BigDecimal("5000.00"))
                                        .runningBalance(new BigDecimal("75000.00"))
                                        .build()))
                        .totalDebit(new BigDecimal("30000.00"))
                        .totalCredit(new BigDecimal("5000.00"))
                        .closingBalance(new BigDecimal("75000.00"))
                        .build()))
                .totalDebit(new BigDecimal("30000.00"))
                .totalCredit(new BigDecimal("5000.00"))
                .build();
    }
}
