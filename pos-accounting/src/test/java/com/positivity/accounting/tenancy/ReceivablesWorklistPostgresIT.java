package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.dto.AgedReceivablesReport;
import com.positivity.accounting.internal.dto.AgedReceivablesRow;
import com.positivity.accounting.internal.dto.CustomerOpenInvoicesPage;
import com.positivity.accounting.internal.dto.OpenInvoiceRow;
import com.positivity.accounting.internal.dto.UnappliedPaymentRow;
import com.positivity.accounting.internal.dto.UnappliedPaymentsPage;
import com.positivity.accounting.internal.service.FinancialReportingService;
import com.positivity.accounting.internal.service.ReceivablesWorklistService;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The receivables worklist reads of #2502 against real Postgres, the real Flyway chain (V7) and
 * row-level security: criteria 1, 5, 6, 10 and 11. Fixtures are written by the owner with explicit
 * tenant ids; every read runs as the non-owner {@code pos_app} role under the tenant it names. Each
 * test uses tenants of its own, so rows other tests leave in the shared database never show.
 *
 * <p>Requires Docker.
 */
@DisplayName("Receivables worklist (#2502, real Postgres)")
class ReceivablesWorklistPostgresIT extends PostgresTenancyTestBase {

    private final JdbcTemplate owner = new JdbcTemplate(ownerDataSource());

    @Autowired
    private ReceivablesWorklistService worklist;

    @Autowired
    private FinancialReportingService financialReportingService;

    @Autowired
    private Clock clock;

    @Test
    @DisplayName("V7 adds the two payment columns and the (tenant, party, status) index")
    void migration() {
        assertThat(owner.queryForList(
                        "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public'"
                                + " AND table_name = 'receivable_payment'"
                                + " AND column_name IN ('source_invoice_id', 'payment_method') ORDER BY column_name",
                        String.class))
                .containsExactly("payment_method", "source_invoice_id");
        assertThat(owner.queryForObject(
                        "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public'"
                                + " AND indexname = 'idx_ext_invoice_party_status'",
                        String.class))
                .contains("ext_invoice")
                .contains("(tenant_id, party_id, status)");
    }

    @Test
    @DisplayName("AC1: three AVAILABLE payments listed oldest first, the FULLY_APPLIED one not; the summary covers"
            + " all three whatever the page size")
    void listsAvailablePaymentsOldestFirst() {
        UUID tenant = tenantWithZone();
        UUID customer = UUIDv7Generator.generate();
        Instant now = Instant.now(clock);
        UUID second = payment(tenant, customer, "200.00", "200.00", "AVAILABLE", now.minusSeconds(200), null, "CARD");
        UUID first = payment(tenant, customer, "100.00", "100.00", "AVAILABLE", now.minusSeconds(300), null, "CASH");
        payment(tenant, customer, "75.00", "0.00", "FULLY_APPLIED", now.minusSeconds(400), null, "CARD");
        UUID third = payment(tenant, customer, "400.00", "350.50", "AVAILABLE", now.minusSeconds(100), null, null);

        UnappliedPaymentsPage all = asTenant(tenant, () -> worklist.listUnappliedPayments(null, 0, 25));
        UnappliedPaymentsPage firstPage = asTenant(tenant, () -> worklist.listUnappliedPayments(null, 0, 1));
        UnappliedPaymentsPage secondPage = asTenant(tenant, () -> worklist.listUnappliedPayments(customer, 1, 1));

        assertThat(all.getItems()).extracting(UnappliedPaymentRow::getPaymentId).containsExactly(first, second, third);
        assertThat(all.getItems().getFirst().getPaymentMethod()).isEqualTo("CASH");
        assertThat(all.getItems().getLast().getUnappliedAmount()).isEqualByComparingTo("350.50");
        for (UnappliedPaymentsPage page : List.of(all, firstPage, secondPage)) {
            assertThat(page.getSummary().getCount()).isEqualTo(3);
            assertThat(page.getSummary().getTotalUnappliedAmount()).isEqualByComparingTo("650.50");
            assertThat(page.getTotalElements()).isEqualTo(3);
        }
        assertThat(firstPage.getItems())
                .extracting(UnappliedPaymentRow::getPaymentId)
                .containsExactly(first);
        assertThat(secondPage.getItems())
                .extracting(UnappliedPaymentRow::getPaymentId)
                .containsExactly(second);
    }

    @Test
    @DisplayName("AC2 against the database: a payment taken against an open invoice of its amount suggests it")
    void remittanceSuggestion() {
        UUID tenant = tenantWithZone();
        UUID customer = UUIDv7Generator.generate();
        UUID invoice = invoice(tenant, customer, "INV-1", "POSTED", "4615.00", LocalDate.now(clock));
        customerParty(tenant, customer, "Rivera Trucking", "CUST-00412");
        payment(tenant, customer, "4615.00", "4615.00", "AVAILABLE", Instant.now(clock), invoice, "CARD");

        UnappliedPaymentRow row = asTenant(tenant, () -> worklist.listUnappliedPayments(null, 0, 25))
                .getItems()
                .getFirst();

        assertThat(row.getCustomerDisplayName()).isEqualTo("Rivera Trucking");
        assertThat(row.getCustomerReference()).isEqualTo("CUST-00412");
        assertThat(row.getSourceInvoiceNumber()).isEqualTo("INV-1");
        assertThat(row.getSuggestion().getReasons())
                .containsExactly("REMITTANCE_REFERENCE", "SAME_CUSTOMER", "EXACT_TOTAL");
        assertThat(row.getSuggestion().getInvoices()).singleElement().satisfies(suggested -> {
            assertThat(suggested.getInvoiceId()).isEqualTo(invoice);
            assertThat(suggested.getSuggestedAmount()).isEqualByComparingTo("4615.00");
        });
        assertThat(row.getSuggestion().getLeftOver()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("AC5: 500.00 less 100.00 applied of which 50.00 is reversed (two 50.00 applications, one reversed in"
            + " full), a posted 30.00 memo, a 20.00 credit and a 40.00 deposit leaves 360.00")
    void balanceNetsEveryTerm() {
        UUID tenant = tenantWithZone();
        UUID customer = UUIDv7Generator.generate();
        UUID invoice = invoice(tenant, customer, "INV-500", "POSTED", "500.00", LocalDate.now(clock));
        UUID source = payment(tenant, customer, "100.00", "0.00", "FULLY_APPLIED", Instant.now(clock), null, null);
        // A reversal always reverses a whole application (reversePaymentApplication), so the reachable state
        // is two 50.00 applications with one reversed: 500 - (100 - 50) - 30 - 20 - 40 = 360.
        application(tenant, source, invoice, customer, "50.00");
        UUID reversed = application(tenant, source, invoice, customer, "50.00");
        reversal(tenant, reversed, "50.00");
        creditMemo(tenant, invoice, customer, "25.00", "5.00", "POSTED");
        creditMemo(tenant, invoice, customer, "99.00", "0.00", "DRAFT");
        customerCreditApplication(tenant, source, invoice, customer, "20.00");
        depositCredit(tenant, invoice, "40.00");

        CustomerOpenInvoicesPage page = asTenant(tenant, () -> worklist.listOpenInvoices(customer, 0, 100));

        assertThat(page.getItems()).singleElement().satisfies(row -> {
            assertThat(row.getBalanceDue()).isEqualByComparingTo("360.00");
            assertThat(row.getArStatus()).isEqualTo("PARTIALLY_PAID");
        });
    }

    @Test
    @DisplayName("AC6: only the POSTED invoice with a balance is open, and the total equals aged receivables today")
    void openInvoicesMatchAgedReceivables() {
        UUID tenant = tenantWithZone();
        UUID customer = UUIDv7Generator.generate();
        UUID other = UUIDv7Generator.generate();
        LocalDate today = LocalDate.now(clock);
        invoice(tenant, customer, "INV-DRAFT", "DRAFT", "70.00", today);
        UUID settled = invoice(tenant, customer, "INV-SETTLED", "FINALIZED", "50.00", today);
        UUID source = payment(tenant, customer, "50.00", "0.00", "FULLY_APPLIED", Instant.now(clock), null, null);
        application(tenant, source, settled, customer, "50.00");
        UUID posted = invoice(tenant, customer, "INV-POSTED", "POSTED", "80.00", today.minusDays(1));
        invoice(tenant, other, "INV-OTHER", "POSTED", "15.00", today);

        CustomerOpenInvoicesPage page = asTenant(tenant, () -> worklist.listOpenInvoices(customer, 0, 100));
        AgedReceivablesReport aged = asTenant(tenant, () -> financialReportingService.generateAgedReceivables(today));

        assertThat(page.getItems()).extracting(OpenInvoiceRow::getInvoiceId).containsExactly(posted);
        // AC7 against the database: due yesterday is one day overdue.
        assertThat(page.getItems().getFirst().isOverdue()).isTrue();
        assertThat(page.getItems().getFirst().getDaysOverdue()).isEqualTo(1);
        BigDecimal agedTotal = aged.getRows().stream()
                .filter(row -> row.getCustomerId().equals(customer))
                .map(AgedReceivablesRow::getTotalOutstanding)
                .findFirst()
                .orElseThrow();
        assertThat(page.getSummary().getTotalBalanceDue())
                .isEqualByComparingTo("80.00")
                .isEqualByComparingTo(agedTotal);
    }

    @Test
    @DisplayName("AC10: tenant B's payments and invoices never appear to tenant A")
    void tenantIsolation() {
        UUID tenantA = tenantWithZone();
        UUID tenantB = tenantWithZone();
        UUID customer = UUIDv7Generator.generate();
        UUID paymentA = payment(tenantA, customer, "10.00", "10.00", "AVAILABLE", Instant.now(clock), null, null);
        payment(tenantB, customer, "20.00", "20.00", "AVAILABLE", Instant.now(clock), null, null);
        UUID invoiceA = invoice(tenantA, customer, "INV-A", "POSTED", "10.00", LocalDate.now(clock));
        invoice(tenantB, customer, "INV-B", "POSTED", "20.00", LocalDate.now(clock));

        UnappliedPaymentsPage payments = asTenant(tenantA, () -> worklist.listUnappliedPayments(customer, 0, 25));
        CustomerOpenInvoicesPage invoices = asTenant(tenantA, () -> worklist.listOpenInvoices(customer, 0, 100));

        assertThat(payments.getItems())
                .extracting(UnappliedPaymentRow::getPaymentId)
                .containsExactly(paymentA);
        assertThat(payments.getSummary().getTotalUnappliedAmount()).isEqualByComparingTo("10.00");
        assertThat(payments.getItems().getFirst().getSuggestion().getInvoices())
                .singleElement()
                .satisfies(suggested -> assertThat(suggested.getInvoiceId()).isEqualTo(invoiceA));
        assertThat(invoices.getItems()).extracting(OpenInvoiceRow::getInvoiceId).containsExactly(invoiceA);
    }

    @Test
    @DisplayName("AC11: a page of 100 open invoices costs the same queries as one — the candidates, five balance"
            + " terms and the accounting time zone")
    void boundedQueries() throws Exception {
        UUID tenant = tenantWithZone();
        UUID customer = UUIDv7Generator.generate();
        UUID first = invoice(tenant, customer, "INV-0", "POSTED", "10.00", LocalDate.now(clock));
        UUID source = payment(tenant, customer, "1.00", "0.00", "FULLY_APPLIED", Instant.now(clock), null, null);
        application(tenant, source, first, customer, "1.00");
        long forOne = statementsFor(tenant, customer, 1);
        for (int n = 1; n < 100; n++) {
            invoice(
                    tenant,
                    customer,
                    "INV-" + n,
                    "POSTED",
                    "10.00",
                    LocalDate.now(clock).plusDays(n));
        }
        long forHundred = statementsFor(tenant, customer, 100);

        assertThat(forHundred).as("a hundred invoices cost no more than one").isEqualTo(forOne);
        assertThat(forOne)
                .as("one candidate query, one grouped query per balance term, and the tenant's accounting time zone"
                        + " (#2558: today is the tenant's day)")
                .isEqualTo(7);
    }

    /**
     * Statements this read issues on the test thread only: scheduled jobs (the outbox processor polls
     * every five seconds) run on other threads and are not counted.
     */
    private long statementsFor(UUID tenant, UUID customer, int expectedRows) throws Exception {
        ThreadStatementCounter.Counted<CustomerOpenInvoicesPage> counted =
                ThreadStatementCounter.count(() -> asTenant(tenant, () -> worklist.listOpenInvoices(customer, 0, 100)));
        assertThat(counted.result().getItems()).hasSize(expectedRows);
        return counted.statements();
    }

    // ---- fixtures (owner, explicit tenant) ----

    private UUID invoice(UUID tenant, UUID party, String number, String status, String total, LocalDate dueDate) {
        UUID id = UUIDv7Generator.generate();
        Instant created = dueDate.minusDays(30).atStartOfDay().toInstant(ZoneOffset.UTC);
        owner.update(
                "INSERT INTO ext_invoice (tenant_id, invoice_id, invoice_number, party_id, status, total,"
                        + " invoice_created_at, finalized_at, due_date, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tenant,
                id,
                number,
                party.toString(),
                status,
                new BigDecimal(total),
                Timestamp.from(created),
                Timestamp.from(created),
                Date.valueOf(dueDate),
                Timestamp.from(Instant.now(clock)));
        return id;
    }

    private UUID payment(
            UUID tenant,
            UUID customer,
            String total,
            String unapplied,
            String status,
            Instant clearedAt,
            @Nullable UUID sourceInvoiceId,
            @Nullable String method) {
        UUID id = UUIDv7Generator.generate();
        owner.update(
                "INSERT INTO receivable_payment (tenant_id, payment_id, customer_id, currency, total_amount,"
                        + " unapplied_amount, status, cleared_at, created_at, source_invoice_id, payment_method)"
                        + " VALUES (?, ?, ?, 'USD', ?, ?, ?, ?, ?, ?, ?)",
                tenant,
                id,
                customer,
                new BigDecimal(total),
                new BigDecimal(unapplied),
                status,
                Timestamp.from(clearedAt),
                Timestamp.from(clearedAt),
                sourceInvoiceId,
                method);
        return id;
    }

    private UUID application(UUID tenant, UUID payment, UUID invoice, UUID customer, String amount) {
        UUID id = UUIDv7Generator.generate();
        owner.update(
                "INSERT INTO payment_application (tenant_id, payment_application_id, payment_id, invoice_id,"
                        + " customer_id, applied_amount, currency, application_timestamp, created_at, created_by,"
                        + " application_request_id, application_source)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 'USD', now(), now(), 'it', ?, 'MANUAL')",
                tenant,
                id,
                payment,
                invoice,
                customer,
                new BigDecimal(amount),
                "it-" + id);
        return id;
    }

    private void reversal(UUID tenant, UUID application, String amount) {
        owner.update(
                "INSERT INTO payment_application_reversal (tenant_id, reversal_id, original_payment_application_id,"
                        + " amount, reversed_at, reversed_by, reason) VALUES (?, ?, ?, ?, now(), 'it', 'reversed in IT')",
                tenant,
                UUIDv7Generator.generate(),
                application,
                new BigDecimal(amount));
    }

    private void creditMemo(UUID tenant, UUID invoice, UUID customer, String amount, String tax, String status) {
        owner.update(
                "INSERT INTO credit_memo (tenant_id, credit_memo_id, customer_id, original_invoice_id, credit_amount,"
                        + " tax_amount_reversed, currency, prior_period_adjustment, creation_timestamp, status,"
                        + " created_by_user_id, reason_code) VALUES (?, ?, ?, ?, ?, ?, 'USD', false, now(), ?, 'it',"
                        + " 'PRICE_ADJUSTMENT')",
                tenant,
                UUIDv7Generator.generate(),
                customer,
                invoice,
                new BigDecimal(amount),
                new BigDecimal(tax),
                status);
    }

    private void customerCreditApplication(
            UUID tenant, UUID sourcePayment, UUID invoice, UUID customer, String amount) {
        UUID credit = UUIDv7Generator.generate();
        owner.update(
                "INSERT INTO customer_credit (tenant_id, credit_id, customer_id, source_payment_id, amount, currency,"
                        + " created_at, applied_amount) VALUES (?, ?, ?, ?, ?, 'USD', now(), ?)",
                tenant,
                credit,
                customer,
                sourcePayment,
                new BigDecimal(amount),
                new BigDecimal(amount));
        owner.update(
                "INSERT INTO customer_credit_transaction (tenant_id, credit_transaction_id, credit_id,"
                        + " transaction_type, invoice_id, amount, currency, request_id, created_at)"
                        + " VALUES (?, ?, ?, 'APPLICATION', ?, ?, 'USD', ?, now())",
                tenant,
                UUIDv7Generator.generate(),
                credit,
                invoice,
                new BigDecimal(amount),
                "it-" + credit);
    }

    private void depositCredit(UUID tenant, UUID invoice, String amount) {
        owner.update(
                "INSERT INTO ext_invoice_deposit_credit_application (tenant_id, application_id, deposit_credit_id,"
                        + " invoice_id, amount_applied, applied_at, source_event_id) VALUES (?, ?, ?, ?, ?, now(), ?)",
                tenant,
                UUIDv7Generator.generate(),
                UUIDv7Generator.generate(),
                invoice,
                new BigDecimal(amount),
                UUIDv7Generator.generate());
    }

    private void customerParty(UUID tenant, UUID party, String name, String number) {
        owner.update(
                "INSERT INTO ext_customer_party (tenant_id, party_id, party_type, display_name, customer_number,"
                        + " status, updated_at) VALUES (?, ?, 'PERSON', ?, ?, 'ACTIVE', now())",
                tenant,
                party,
                name,
                number);
    }
}
