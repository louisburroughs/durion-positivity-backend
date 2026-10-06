package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.AccountingPostgresContainer;
import com.positivity.accounting.internal.config.TestSecurityConfig;
import com.positivity.accounting.internal.dto.AccountDrilldownResponse;
import com.positivity.accounting.internal.dto.IncomeStatementReport;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.IdempotencyKeyRepository;
import com.positivity.accounting.internal.repository.InvoiceGlPostingRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.domainevents.invoice.InvoiceUpdatedV1;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real-Postgres IT for the income statement built from the shipped seed (issue #2394). Runs the
 * full Flyway chain + repeatable seed on a Testcontainers Postgres and adds no account, GL mapping
 * or statement-line mapping of its own: the seeded {@code REVENUE} line over account 4000 Service
 * Revenue and the seeded {@code INVOICE_REVENUE} posting category are the subject of the test.
 *
 * <p>Revenue reaches the ledger the way invoice finalization puts it there (#1843):
 * {@link InvoiceRevenuePostingService#postRevenue} with a {@code FINALIZED} invoice fact, which posts
 * {@code Dr Accounts Receivable / Cr Service Revenue / Cr Sales Tax Payable}. The statement must then
 * report that revenue as a positive line, a positive {@code totalRevenue} and the matching
 * {@code netIncome}. Before the fix the seeded line code matched no revenue prefix and the credit
 * balance was summed as stored, so the line read negative and both totals read zero.
 *
 * <p>Requires Docker.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@DisplayName("Income statement from the shipped seed (#2394, real Postgres)")
class IncomeStatementSeedRevenueIT {

    /**
     * A database of this IT's own inside the shared container: it commits its fixtures and clears
     * whole tables, so it must not share the default database with tests that read the seed.
     */
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        AccountingPostgresContainer.registerIsolatedDatabase(registry, "income-statement-seed");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        // Schema + seed come from the real Flyway chain, not Hibernate DDL.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    /** The statement line the seed maps account 4000 Service Revenue to (CAP:550 S35, #2524). */
    private static final String SEEDED_REVENUE_LINE = "IS_SALES";

    private static final BigDecimal TAX = new BigDecimal("80.00");
    private static final BigDecimal TOTAL = new BigDecimal("1080.00");
    private static final BigDecimal REVENUE = new BigDecimal("1000.00");

    @Autowired
    private Clock clock;

    @Autowired
    private InvoiceRevenuePostingService invoiceRevenuePostingService;

    @Autowired
    private FinancialReportingService financialReportingService;

    @Autowired
    private InvoiceGlPostingRepository invoiceGlPostingRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private AccountingSequenceRepository sequenceRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @AfterEach
    void cleanUp() {
        invoiceGlPostingRepository.deleteAll();
        journalEntryRepository.deleteAll();
        sequenceRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
    }

    @Test
    @DisplayName("One finalized invoice reads as positive revenue, and net income matches it")
    void finalizedInvoiceIsPositiveRevenueAndNetIncome() {
        Instant finalizedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        LocalDate day = LocalDate.ofInstant(finalizedAt, clock.getZone());

        invoiceRevenuePostingService.postRevenue(invoiceFact(UUID.randomUUID(), "FINALIZED", finalizedAt));

        IncomeStatementReport report = financialReportingService.generateIncomeStatement(day, day);

        // The seed's named income-statement lines are all present; the two without activity read zero,
        // and no computed line appears because every posted account has a named line or belongs to
        // the balance sheet (#2524).
        assertThat(report.getLineItems())
                .containsOnlyKeys(SEEDED_REVENUE_LINE, "IS_COST_OF_PARTS_SOLD", "IS_CARD_PROCESSING_FEES");
        assertThat(report.getLineItems().get("IS_COST_OF_PARTS_SOLD")).isEqualByComparingTo("0");
        assertThat(report.getLineItems().get(SEEDED_REVENUE_LINE))
                .as("seeded revenue line, net of tax")
                .isEqualByComparingTo(REVENUE);
        assertThat(report.getTotalRevenue()).as("totalRevenue").isEqualByComparingTo(REVENUE);
        assertThat(report.getTotalExpenses()).as("totalExpenses").isEqualByComparingTo("0");
        assertThat(report.getNetIncome()).as("netIncome").isEqualByComparingTo(REVENUE);

        List<AccountDrilldownResponse> accounts =
                financialReportingService.drilldownToAccounts(SEEDED_REVENUE_LINE, day, day);
        assertThat(accounts)
                .singleElement()
                .satisfies(account -> assertThat(account.getBalance())
                        .as("drill-down adds up to the line")
                        .isEqualByComparingTo(REVENUE));
    }

    @Test
    @DisplayName("Reverting the invoice takes the revenue back off the statement")
    void revertedInvoiceNetsRevenueToZero() {
        Instant finalizedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        LocalDate day = LocalDate.ofInstant(finalizedAt, clock.getZone());
        UUID invoiceId = UUID.randomUUID();

        invoiceRevenuePostingService.postRevenue(invoiceFact(invoiceId, "FINALIZED", finalizedAt));
        invoiceRevenuePostingService.reverseRevenue(invoiceFact(invoiceId, "DRAFT", finalizedAt), finalizedAt);

        IncomeStatementReport report = financialReportingService.generateIncomeStatement(day, day);

        assertThat(report.getLineItems().get(SEEDED_REVENUE_LINE)).isEqualByComparingTo("0");
        assertThat(report.getTotalRevenue()).isEqualByComparingTo("0");
        assertThat(report.getNetIncome()).isEqualByComparingTo("0");
    }

    /** An {@code invoice.invoice.updated} fact for a 1,000.00 sale plus 80.00 tax. */
    private static InvoiceUpdatedV1 invoiceFact(UUID invoiceId, String status, Instant finalizedAt) {
        return new InvoiceUpdatedV1(
                invoiceId,
                "INV-2394-0001",
                null,
                null,
                null,
                null,
                status,
                REVENUE,
                TAX,
                TOTAL,
                BigDecimal.ZERO,
                finalizedAt,
                finalizedAt,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
