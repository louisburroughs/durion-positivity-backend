package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.dto.TaxLiabilityReport;
import com.positivity.accounting.internal.dto.TaxLiabilityRow;
import com.positivity.accounting.internal.entity.CreditMemo;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.ExtInvoiceTax;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.CreditMemoStatus;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.repository.CreditMemoRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceTaxRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real-Postgres IT for the Sales-Tax Liability report (parity-T8, issue #966).
 * Runs the full Flyway chain + repeatable seed (which carries the Sales-Tax
 * Payable account, code 2200) on a Testcontainers Postgres, so the report's
 * jurisdiction aggregation, POSTED credit-memo pro-rata netting, and GL-drift
 * reconciliation are exercised end-to-end against the real ledger — H2 does not
 * enforce the Postgres balance trigger the seeded fixtures rely on.
 *
 * <p>Fixtures: two invoices finalized in-period across WA state / King county /
 * Seattle city (one exempt resale line), a matching balanced {@code Cr 2200}
 * invoice-tax journal entry, one POSTED credit memo reversing $25 with its
 * balanced {@code Dr 2200} entry. On this balanced ledger the report's net tax
 * equals the 2200 credit-normal period activity and GL drift is exactly zero.
 *
 * <p>Skipped cleanly when Docker is unavailable ({@code disabledWithoutDocker}).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Transactional
@DisplayName("Sales-Tax Liability report (parity-T8 #966, real Postgres)")
class TaxLiabilityReportIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        // Schema + seed come from the real Flyway chain, not Hibernate DDL.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    private static final LocalDate START = LocalDate.of(2026, 6, 1);
    private static final LocalDate END = LocalDate.of(2026, 6, 30);

    @Autowired
    private FinancialReportingService financialReportingService;

    @Autowired
    private ExtInvoiceRepository extInvoiceRepository;

    @Autowired
    private ExtInvoiceTaxRepository extInvoiceTaxRepository;

    @Autowired
    private CreditMemoRepository creditMemoRepository;

    @Autowired
    private GLAccountRepository glAccountRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    // Test runs in a rolled-back transaction (fixtures + report share one persistence
    // context, so seeded GL accounts stay managed); no explicit cleanup needed.

    @Test
    @DisplayName("Per-jurisdiction taxable/exempt/gross/net reconcile to the cent and GL drift == 0")
    void reconcilesToTheCentWithZeroDrift() {
        UUID inv1 = UUID.randomUUID();
        UUID inv2 = UUID.randomUUID();

        saveInvoice(inv1);
        saveInvoice(inv2);

        // INV1: state 65, county 35, city 25 (1000 base each) + a 500 exempt resale line.
        saveTax(inv1, "STATE", "WA", "1000.00", "65.00", false, null);
        saveTax(inv1, "COUNTY", "KING", "1000.00", "35.00", false, null);
        saveTax(inv1, "CITY", "SEATTLE", "1000.00", "25.00", false, null);
        saveTax(inv1, "STATE", "WA", "500.00", "0.00", true, "RESALE");
        // INV2: state 130 on 2000 base.
        saveTax(inv2, "STATE", "WA", "2000.00", "130.00", false, null);

        // One POSTED credit against INV1 reversing $25 of tax, posted in-period.
        saveCredit(inv1, "25.00");

        GLAccount ar = account("1200");
        GLAccount taxPayable = account("2200");

        // Invoice-side GL: Dr AR 255 / Cr 2200 255 (total collected tax), dated in-period.
        postBalancedEntry("JE-T8-INV", LocalDateTime.of(2026, 6, 15, 0, 0), ar, taxPayable, new BigDecimal("255.00"));
        // Credit-side GL: Dr 2200 25 / Cr AR 25, dated in-period.
        postBalancedEntry("JE-T8-CRD", LocalDateTime.of(2026, 6, 20, 0, 0), taxPayable, ar, new BigDecimal("25.00"));

        TaxLiabilityReport report = financialReportingService.generateTaxLiability(START, END);

        assertThat(report.getRows())
                .extracting(TaxLiabilityRow::getJurisdictionType, TaxLiabilityRow::getJurisdictionCode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("STATE", "WA"),
                        org.assertj.core.groups.Tuple.tuple("COUNTY", "KING"),
                        org.assertj.core.groups.Tuple.tuple("CITY", "SEATTLE"));

        TaxLiabilityRow wa = report.getRows().get(0);
        assertThat(wa.getTaxableBase()).isEqualByComparingTo("3000.00");
        assertThat(wa.getExemptBase()).isEqualByComparingTo("500.00");
        assertThat(wa.getExemptionReasons()).containsExactly("RESALE");
        assertThat(wa.getTaxCollectedGross()).isEqualByComparingTo("195.00");
        assertThat(wa.getCreditsNetted()).isEqualByComparingTo("13.00");
        assertThat(wa.getNetTax()).isEqualByComparingTo("182.00");

        TaxLiabilityRow king = report.getRows().get(1);
        assertThat(king.getTaxCollectedGross()).isEqualByComparingTo("35.00");
        assertThat(king.getCreditsNetted()).isEqualByComparingTo("7.00");
        assertThat(king.getNetTax()).isEqualByComparingTo("28.00");

        TaxLiabilityRow seattle = report.getRows().get(2);
        assertThat(seattle.getTaxCollectedGross()).isEqualByComparingTo("25.00");
        assertThat(seattle.getCreditsNetted()).isEqualByComparingTo("5.00");
        assertThat(seattle.getNetTax()).isEqualByComparingTo("20.00");

        assertThat(report.getTotalTaxableBase()).isEqualByComparingTo("5000.00");
        assertThat(report.getTotalExemptBase()).isEqualByComparingTo("500.00");
        assertThat(report.getTotalTaxCollectedGross()).isEqualByComparingTo("255.00");
        assertThat(report.getTotalCreditsNetted()).isEqualByComparingTo("25.00");
        assertThat(report.getTotalNetTax()).isEqualByComparingTo("230.00");

        // GL drift reconciles to exactly zero on a balanced ledger.
        assertThat(report.getReconciliation().getTaxPayableAccountCode()).isEqualTo("2200");
        assertThat(report.getReconciliation().getGlNetActivity()).isEqualByComparingTo("230.00");
        assertThat(report.getReconciliation().getReportNetTax()).isEqualByComparingTo("230.00");
        assertThat(report.getReconciliation().getDrift()).isEqualByComparingTo("0");
        assertThat(report.getReconciliation().getReconciled()).isTrue();
    }

    // ===== fixtures =====

    private void saveInvoice(UUID invoiceId) {
        ExtInvoice invoice = ExtInvoice.builder()
                .invoiceId(invoiceId)
                .workorderId(UUID.randomUUID())
                .partyId(UUID.randomUUID().toString())
                .status("FINALIZED")
                .finalizedAt(Instant.parse("2026-06-15T00:00:00Z"))
                .invoiceCreatedAt(Instant.parse("2026-06-15T00:00:00Z"))
                .aggregateVersion(1L)
                .updatedAt(Instant.parse("2026-06-30T00:00:00Z"))
                .build();
        extInvoiceRepository.save(invoice);
    }

    private void saveTax(
            UUID invoiceId, String type, String code, String base, String tax, boolean exempt, String reason) {
        ExtInvoiceTax row = ExtInvoiceTax.builder()
                .invoiceId(invoiceId)
                .lineItemId(code + "-line")
                .jurisdictionType(type)
                .jurisdictionCode(code)
                .rate(new BigDecimal("0.065000"))
                .taxableBase(new BigDecimal(base))
                .taxAmount(new BigDecimal(tax))
                .exempt(exempt)
                .exemptionReasonCode(reason)
                .aggregateVersion(1L)
                .updatedAt(Instant.parse("2026-06-15T00:00:00Z"))
                .build();
        extInvoiceTaxRepository.save(row);
    }

    private void saveCredit(UUID originalInvoiceId, String taxReversed) {
        CreditMemo credit = new CreditMemo();
        credit.setOriginalInvoiceId(originalInvoiceId);
        credit.setCustomerId(UUID.randomUUID());
        credit.setCreditAmount(new BigDecimal("100.00"));
        credit.setTaxAmountReversed(new BigDecimal(taxReversed));
        credit.setReasonCode("RETURN");
        credit.setStatus(CreditMemoStatus.POSTED);
        credit.setCreationTimestamp(Instant.parse("2026-06-20T00:00:00Z"));
        credit.setPostedTimestamp(Instant.parse("2026-06-20T00:00:00Z"));
        credit.setCreatedByUserId("t8-it");
        credit.setCurrency("USD");
        creditMemoRepository.save(credit);
    }

    private GLAccount account(String code) {
        return glAccountRepository
                .findByAccountCode(code)
                .orElseThrow(() -> new IllegalStateException("Seed missing GL account " + code));
    }

    private void postBalancedEntry(
            String entryNumber,
            LocalDateTime txDate,
            GLAccount debitAccount,
            GLAccount creditAccount,
            BigDecimal amount) {
        JournalEntry entry = new JournalEntry();
        entry.setStatus(JournalEntryStatus.POSTED);
        entry.setEntryNumber(entryNumber);
        entry.setTransactionDate(txDate);
        entry.setSourceEventType("T8_TEST");
        entry.setCreatedBy("t8-it");
        entry.setModifiedBy("t8-it");
        entry.setTotalDebits(amount);
        entry.setTotalCredits(amount);
        entry.setIsBalanced(true);
        entry.addLine(line(entry, 1, debitAccount, amount, BigDecimal.ZERO));
        entry.addLine(line(entry, 2, creditAccount, BigDecimal.ZERO, amount));
        journalEntryRepository.save(entry);
    }

    private static JournalEntryLine line(
            JournalEntry entry, int lineNumber, GLAccount account, BigDecimal debit, BigDecimal credit) {
        JournalEntryLine line = new JournalEntryLine();
        line.setJournalEntry(entry);
        line.setLineNumber(lineNumber);
        line.setGlAccount(account);
        line.setAccountCode(account.getAccountCode());
        line.setAccountName(account.getAccountName());
        line.setDebitAmount(debit);
        line.setCreditAmount(credit);
        line.setDescription("t8 test line");
        return line;
    }
}
