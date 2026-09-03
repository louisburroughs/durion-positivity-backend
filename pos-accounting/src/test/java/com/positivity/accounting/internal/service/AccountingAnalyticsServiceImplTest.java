package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.CollectionsAnalyticsReport;
import com.positivity.accounting.internal.dto.PaymentLagCohortRow;
import com.positivity.accounting.internal.dto.PaymentLagCohortsReport;
import com.positivity.accounting.internal.dto.VendorSpendReport;
import com.positivity.accounting.internal.dto.VendorSpendRow;
import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.entity.CustomerCreditTransaction;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.PaymentApplication;
import com.positivity.accounting.internal.entity.Vendor;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.enums.CustomerCreditTransactionType;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import com.positivity.accounting.internal.repository.CustomerCreditTransactionRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceDepositCreditApplicationRepository;
import com.positivity.accounting.internal.repository.ExtInvoicePaymentReversalRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationReversalRepository;
import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.repository.VendorRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link AccountingAnalyticsServiceImpl}: invoiced-vs-collected cohort separation,
 * the zero-invoiced ratio edge case (E2), the payment-lag boundary / unpaid / partial-payment
 * cohort rules (E3), and the vendor-spend paidAmount/billsIssuedInWindow population split (E8).
 */
@ExtendWith(MockitoExtension.class)
class AccountingAnalyticsServiceImplTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ExtInvoiceRepository extInvoiceRepository;

    @Mock
    private PaymentApplicationRepository paymentApplicationRepository;

    @Mock
    private PaymentApplicationReversalRepository paymentApplicationReversalRepository;

    @Mock
    private ExtInvoicePaymentReversalRepository extInvoicePaymentReversalRepository;

    @Mock
    private ExtInvoiceDepositCreditApplicationRepository extInvoiceDepositCreditApplicationRepository;

    @Mock
    private ReceivablePaymentRepository receivablePaymentRepository;

    @Mock
    private CustomerCreditTransactionRepository customerCreditTransactionRepository;

    @Mock
    private APPaymentRepository apPaymentRepository;

    @Mock
    private VendorBillRepository vendorBillRepository;

    @Mock
    private VendorRepository vendorRepository;

    private AccountingAnalyticsServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AccountingAnalyticsServiceImpl(
                TEST_CLOCK,
                extInvoiceRepository,
                paymentApplicationRepository,
                paymentApplicationReversalRepository,
                extInvoicePaymentReversalRepository,
                extInvoiceDepositCreditApplicationRepository,
                receivablePaymentRepository,
                customerCreditTransactionRepository,
                apPaymentRepository,
                vendorBillRepository,
                vendorRepository);
    }

    private static ExtInvoice invoice(UUID id, Instant finalizedAt, String total) {
        return ExtInvoice.builder()
                .invoiceId(id)
                .workorderId(UUID.randomUUID())
                .status("FINALIZED")
                .total(new BigDecimal(total))
                .finalizedAt(finalizedAt)
                .invoiceCreatedAt(finalizedAt)
                .aggregateVersion(1L)
                .build();
    }

    /** The document a deposit-take order renders for the down-payment itself (#1623). */
    private static ExtInvoice depositTakeInvoice(UUID id, Instant finalizedAt, String total) {
        ExtInvoice invoice = invoice(id, finalizedAt, total);
        invoice.setDepositSourceType("WORKORDER");
        return invoice;
    }

    private static PaymentApplication application(
            UUID invoiceId, Instant timestamp, String appliedAmount, String balanceAfter) {
        PaymentApplication app = new PaymentApplication();
        app.setPaymentApplicationId(UUID.randomUUID());
        app.setInvoiceId(invoiceId);
        app.setCustomerId(UUID.randomUUID());
        app.setCurrency("USD");
        app.setAppliedAmount(new BigDecimal(appliedAmount));
        app.setInvoiceBalanceAfter(balanceAfter == null ? null : new BigDecimal(balanceAfter));
        app.setApplicationTimestamp(timestamp);
        app.setApplicationRequestId(UUID.randomUUID().toString());
        app.setCreatedBy("test");
        return app;
    }

    private static APPayment settledPayment(UUID vendorId, String vendorName, LocalDateTime paymentDate, String gross) {
        APPayment payment = new APPayment();
        payment.setPaymentId(UUID.randomUUID());
        payment.setPaymentRef(UUID.randomUUID().toString());
        payment.setVendorId(vendorId);
        payment.setVendorName(vendorName);
        payment.setGrossAmount(new BigDecimal(gross));
        payment.setStatus(APPaymentStatus.GATEWAY_SUCCEEDED);
        payment.setPaymentDate(paymentDate);
        payment.setCreatedBy("test");
        return payment;
    }

    private static VendorBill bill(UUID vendorId, String vendorName, LocalDateTime billDate, String total) {
        VendorBill bill = new VendorBill();
        bill.setVendorBillId(UUID.randomUUID());
        bill.setVendorId(vendorId);
        bill.setVendorName(vendorName);
        bill.setBillNumber("BILL-" + UUID.randomUUID());
        bill.setBillDate(billDate);
        bill.setTotalAmount(new BigDecimal(total));
        bill.setCreatedBy("test");
        return bill;
    }

    @Nested
    @DisplayName("getCollectionsAnalytics (E2)")
    class CollectionsAnalyticsTests {

        @Test
        @DisplayName("Rejects endDate before startDate")
        void rejectsInvalidRange() {
            assertThatThrownBy(
                            () -> service.getCollectionsAnalytics(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 6, 1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Sums invoiced and collected as independent cohorts and derives the rate")
        void sumsIndependentCohorts() {
            LocalDate start = LocalDate.of(2026, 6, 1);
            LocalDate end = LocalDate.of(2026, 6, 30);

            when(extInvoiceRepository.findByFinalizedAtBetween(any(), any()))
                    .thenReturn(List.of(
                            invoice(UUID.randomUUID(), Instant.parse("2026-06-05T00:00:00Z"), "1000.00"),
                            invoice(UUID.randomUUID(), Instant.parse("2026-06-20T00:00:00Z"), "500.00")));
            when(paymentApplicationRepository.findByApplicationTimestampBetween(any(), any()))
                    .thenReturn(List.of(
                            // Settles an invoice finalized in an entirely different (earlier) period —
                            // proving invoiced and collected are independent cohorts.
                            application(UUID.randomUUID(), Instant.parse("2026-06-10T00:00:00Z"), "1200.00", "0.00")));
            when(paymentApplicationReversalRepository.sumAmountByReversedAtBetween(any(), any()))
                    .thenReturn(BigDecimal.ZERO);

            CollectionsAnalyticsReport report = service.getCollectionsAnalytics(start, end);

            assertThat(report.getInvoiced()).isEqualByComparingTo("1500.00");
            assertThat(report.getCollected()).isEqualByComparingTo("1200.00");
            assertThat(report.getApplicationReversals()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(report.getCollectionRatePct()).isEqualByComparingTo("80.00");
            assertThat(report.getGeneratedAt()).isEqualTo(TEST_CLOCK.instant());
        }

        @Test
        @DisplayName("Excludes deposit-take invoices from invoiced and the rate denominator (#1623)")
        void excludesDepositTakeInvoicesFromInvoiced() {
            // The issue's motivating shape: a $500 deposit taken on a $2,000 job. Both documents
            // finalize (here in one window for brevity); invoiced must report the $2,000 sale
            // once, not $2,500 across the deposit-take and gross settlement documents.
            when(extInvoiceRepository.findByFinalizedAtBetween(any(), any()))
                    .thenReturn(List.of(
                            depositTakeInvoice(UUID.randomUUID(), Instant.parse("2026-06-05T00:00:00Z"), "500.00"),
                            invoice(UUID.randomUUID(), Instant.parse("2026-06-20T00:00:00Z"), "2000.00")));
            when(paymentApplicationRepository.findByApplicationTimestampBetween(any(), any()))
                    .thenReturn(List.of(
                            application(UUID.randomUUID(), Instant.parse("2026-06-10T00:00:00Z"), "1000.00", "0.00")));
            when(paymentApplicationReversalRepository.sumAmountByReversedAtBetween(any(), any()))
                    .thenReturn(BigDecimal.ZERO);

            CollectionsAnalyticsReport report =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

            assertThat(report.getInvoiced()).isEqualByComparingTo("2000.00");
            assertThat(report.getCollectionRatePct()).isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("A window of only deposit-take invoices reports invoiced=0 and a null rate (#1623)")
        void depositTakeOnlyWindowReportsZeroInvoiced() {
            when(extInvoiceRepository.findByFinalizedAtBetween(any(), any()))
                    .thenReturn(List.of(
                            depositTakeInvoice(UUID.randomUUID(), Instant.parse("2026-06-05T00:00:00Z"), "500.00")));
            when(paymentApplicationRepository.findByApplicationTimestampBetween(any(), any()))
                    .thenReturn(List.of());
            when(paymentApplicationReversalRepository.sumAmountByReversedAtBetween(any(), any()))
                    .thenReturn(BigDecimal.ZERO);

            CollectionsAnalyticsReport report =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

            assertThat(report.getInvoiced()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(report.getCollectionRatePct()).isNull();
        }

        @Test
        @DisplayName("collectionRatePct is null (not a divide-by-zero or a misleading 0) when invoiced is zero")
        void nullRateWhenInvoicedIsZero() {
            when(extInvoiceRepository.findByFinalizedAtBetween(any(), any())).thenReturn(List.of());
            when(paymentApplicationRepository.findByApplicationTimestampBetween(any(), any()))
                    .thenReturn(List.of(
                            application(UUID.randomUUID(), Instant.parse("2026-06-10T00:00:00Z"), "300.00", "0.00")));

            CollectionsAnalyticsReport report =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

            assertThat(report.getInvoiced()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(report.getCollected()).isEqualByComparingTo("300.00");
            assertThat(report.getCollectionRatePct()).isNull();
        }

        @Test
        @DisplayName("Returns zeros and a null rate for a window with no invoices and no applications")
        void returnsZeroesWhenEmpty() {
            when(extInvoiceRepository.findByFinalizedAtBetween(any(), any())).thenReturn(List.of());
            when(paymentApplicationRepository.findByApplicationTimestampBetween(any(), any()))
                    .thenReturn(List.of());
            when(paymentApplicationReversalRepository.sumAmountByReversedAtBetween(any(), any()))
                    .thenReturn(BigDecimal.ZERO);

            CollectionsAnalyticsReport report =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

            assertThat(report.getInvoiced()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(report.getCollected()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(report.getApplicationReversals()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(report.getCollectionRatePct()).isNull();
        }

        /**
         * Stubs the three window-scoped reads from one fixture so a single dataset can be queried
         * through several different windows in one test — which is what the movement-basis
         * (non-restatement) and additivity cases below actually assert.
         */
        private void stubWindowedFixture(
                List<ExtInvoice> invoices,
                List<PaymentApplication> applications,
                List<Map.Entry<Instant, String>> reversals) {
            stubWindowedFixture(invoices, applications, reversals, List.of(), List.of(), List.of(), List.of());
        }

        /**
         * Full sibling of the three-feed fixture above (ADR-0057 Implementation Notes / Testing):
         * every window-scoped sum answers by the ACTUAL start/end instants passed for its own
         * invocation, keyed off the fixture data, rather than an {@code any()} stub returning one
         * fixed value regardless of window or timestamp column — so swapping the query's timestamp
         * column, or widening its bounds, fails a test instead of passing unnoticed.
         *
         * @param refundReplicaEntries pos-invoice refund replica rows (reversedAt, amount) —
         *     backs {@code extInvoicePaymentReversalRepository.sumAmountByReversedAtBetween}
         * @param depositCreditApplications deposit-credit draw-down rows (appliedAt, amount) —
         *     backs {@code extInvoiceDepositCreditApplicationRepository.sumAmountAppliedByAppliedAtBetween}
         * @param receivedPayments cash-received rows (clearedAt, amount) — backs {@code
         *     receivablePaymentRepository.sumTotalAmountByClearedAtBetween}
         * @param customerCreditTransactions typed customer-credit subledger rows (type, createdAt,
         *     amount) — backs {@code
         *     customerCreditTransactionRepository.sumAmountByTypeAndCreatedAtBetween}, filtered by
         *     BOTH the requested type and the actual window
         */
        private void stubWindowedFixture(
                List<ExtInvoice> invoices,
                List<PaymentApplication> applications,
                List<Map.Entry<Instant, String>> reversals,
                List<Map.Entry<Instant, String>> refundReplicaEntries,
                List<Map.Entry<Instant, String>> depositCreditApplications,
                List<Map.Entry<Instant, String>> receivedPayments,
                List<CreditTxnFixture> customerCreditTransactions) {

            when(extInvoiceRepository.findByFinalizedAtBetween(any(), any()))
                    .thenAnswer(call -> invoices.stream()
                            .filter(i -> within(i.getFinalizedAt(), call.getArgument(0), call.getArgument(1)))
                            .toList());
            when(paymentApplicationRepository.findByApplicationTimestampBetween(any(), any()))
                    .thenAnswer(call -> applications.stream()
                            .filter(a -> within(a.getApplicationTimestamp(), call.getArgument(0), call.getArgument(1)))
                            .toList());
            when(paymentApplicationReversalRepository.sumAmountByReversedAtBetween(any(), any()))
                    .thenAnswer(call -> reversals.stream()
                            .filter(r -> within(r.getKey(), call.getArgument(0), call.getArgument(1)))
                            .map(r -> new BigDecimal(r.getValue()))
                            .reduce(BigDecimal.ZERO, BigDecimal::add));
            when(extInvoicePaymentReversalRepository.sumAmountByReversedAtBetween(any(), any()))
                    .thenAnswer(call -> sumWithin(refundReplicaEntries, call.getArgument(0), call.getArgument(1)));
            when(extInvoiceDepositCreditApplicationRepository.sumAmountAppliedByAppliedAtBetween(any(), any()))
                    .thenAnswer(call -> sumWithin(depositCreditApplications, call.getArgument(0), call.getArgument(1)));
            when(receivablePaymentRepository.sumTotalAmountByClearedAtBetween(any(), any()))
                    .thenAnswer(call -> sumWithin(receivedPayments, call.getArgument(0), call.getArgument(1)));
            when(customerCreditTransactionRepository.sumAmountByTypeAndCreatedAtBetween(any(), any(), any()))
                    .thenAnswer(call -> customerCreditTransactions.stream()
                            .filter(e -> e.type() == call.getArgument(0))
                            .filter(e -> within(e.timestamp(), call.getArgument(1), call.getArgument(2)))
                            .map(CreditTxnFixture::amount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add));
        }

        private BigDecimal sumWithin(List<Map.Entry<Instant, String>> entries, Instant start, Instant end) {
            return entries.stream()
                    .filter(e -> within(e.getKey(), start, end))
                    .map(e -> new BigDecimal(e.getValue()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        private boolean within(Instant value, Instant start, Instant end) {
            return !value.isBefore(start) && !value.isAfter(end);
        }

        /** One row of the typed customer-credit subledger fixture for {@link #stubWindowedFixture}. */
        private record CreditTxnFixture(CustomerCreditTransactionType type, Instant timestamp, BigDecimal amount) {}

        @Test
        @DisplayName("A reversal recorded in the same window as its application nets that application to zero")
        void reversalInSameWindowNetsToZero() {
            stubWindowedFixture(
                    List.of(invoice(UUID.randomUUID(), Instant.parse("2026-06-02T00:00:00Z"), "1000.00")),
                    List.of(application(UUID.randomUUID(), Instant.parse("2026-06-10T00:00:00Z"), "500.00", "500.00")),
                    List.of(Map.entry(Instant.parse("2026-06-12T00:00:00Z"), "500.00")));

            CollectionsAnalyticsReport report =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

            assertThat(report.getInvoiced()).isEqualByComparingTo("1000.00");
            assertThat(report.getCollected()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(report.getApplicationReversals()).isEqualByComparingTo("500.00");
            assertThat(report.getCollectionRatePct()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("A reversal in a LATER window reduces that later window and never restates the earlier one")
        void reversalInLaterWindowDoesNotRestateEarlierWindow() {
            stubWindowedFixture(
                    List.of(
                            invoice(UUID.randomUUID(), Instant.parse("2026-01-05T00:00:00Z"), "1000.00"),
                            invoice(UUID.randomUUID(), Instant.parse("2026-03-05T00:00:00Z"), "400.00")),
                    List.of(application(UUID.randomUUID(), Instant.parse("2026-01-15T00:00:00Z"), "1000.00", "0.00")),
                    List.of(Map.entry(Instant.parse("2026-03-20T00:00:00Z"), "1000.00")));

            CollectionsAnalyticsReport january =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
            CollectionsAnalyticsReport march =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

            // January is closed and must read exactly as it did before the March reversal existed.
            assertThat(january.getCollected()).isEqualByComparingTo("1000.00");
            assertThat(january.getApplicationReversals()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(january.getCollectionRatePct()).isEqualByComparingTo("100.00");

            // March absorbs the whole reversal.
            assertThat(march.getCollected()).isEqualByComparingTo("-1000.00");
            assertThat(march.getApplicationReversals()).isEqualByComparingTo("1000.00");
        }

        @Test
        @DisplayName("Movement basis is additive: Jan + Feb + Mar equals the single Jan-Mar window")
        void subWindowsAreAdditive() {
            stubWindowedFixture(
                    List.of(
                            invoice(UUID.randomUUID(), Instant.parse("2026-01-05T00:00:00Z"), "1200.00"),
                            invoice(UUID.randomUUID(), Instant.parse("2026-02-05T00:00:00Z"), "300.00"),
                            invoice(UUID.randomUUID(), Instant.parse("2026-03-05T00:00:00Z"), "500.00")),
                    List.of(
                            application(UUID.randomUUID(), Instant.parse("2026-01-15T00:00:00Z"), "1000.00", "200.00"),
                            application(UUID.randomUUID(), Instant.parse("2026-02-15T00:00:00Z"), "300.00", "0.00"),
                            application(UUID.randomUUID(), Instant.parse("2026-03-15T00:00:00Z"), "200.00", "300.00")),
                    List.of(
                            Map.entry(Instant.parse("2026-02-20T00:00:00Z"), "100.00"),
                            Map.entry(Instant.parse("2026-03-25T00:00:00Z"), "250.00")));

            CollectionsAnalyticsReport january =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
            CollectionsAnalyticsReport february =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));
            CollectionsAnalyticsReport march =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
            CollectionsAnalyticsReport quarter =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));

            assertThat(january.getCollected()).isEqualByComparingTo("1000.00");
            assertThat(february.getCollected()).isEqualByComparingTo("200.00");
            assertThat(march.getCollected()).isEqualByComparingTo("-50.00");

            BigDecimal summedCollected =
                    january.getCollected().add(february.getCollected()).add(march.getCollected());
            BigDecimal summedReversals = january.getApplicationReversals()
                    .add(february.getApplicationReversals())
                    .add(march.getApplicationReversals());
            BigDecimal summedInvoiced =
                    january.getInvoiced().add(february.getInvoiced()).add(march.getInvoiced());

            assertThat(summedCollected).isEqualByComparingTo(quarter.getCollected());
            assertThat(summedReversals).isEqualByComparingTo(quarter.getApplicationReversals());
            assertThat(summedInvoiced).isEqualByComparingTo(quarter.getInvoiced());
            assertThat(quarter.getCollected()).isEqualByComparingTo("1150.00");
            assertThat(quarter.getApplicationReversals()).isEqualByComparingTo("350.00");
        }

        @Test
        @DisplayName("refunded (replica leg) is attributed to the ACTUAL reversedAt window, not any() (ADR-0057"
                + " Implementation Notes / Testing)")
        void refundedReplicaLegPinnedToActualWindow() {
            stubWindowedFixture(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(
                            Map.entry(Instant.parse("2026-01-10T00:00:00Z"), "200.00"),
                            Map.entry(Instant.parse("2026-02-10T00:00:00Z"), "300.00")),
                    List.of(),
                    List.of(),
                    List.of());

            CollectionsAnalyticsReport january =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
            CollectionsAnalyticsReport february =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

            assertThat(january.getRefunded()).isEqualByComparingTo("200.00");
            assertThat(february.getRefunded()).isEqualByComparingTo("300.00");
        }

        @Test
        @DisplayName("nonCashSettled (deposit-credit leg) is attributed to the ACTUAL appliedAt window, not"
                + " any() (ADR-0057 Implementation Notes / Testing)")
        void nonCashSettledDepositCreditLegPinnedToActualWindow() {
            stubWindowedFixture(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(
                            Map.entry(Instant.parse("2026-01-10T00:00:00Z"), "400.00"),
                            Map.entry(Instant.parse("2026-02-10T00:00:00Z"), "600.00")),
                    List.of(),
                    List.of());

            CollectionsAnalyticsReport january =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
            CollectionsAnalyticsReport february =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

            assertThat(january.getNonCashSettled()).isEqualByComparingTo("400.00");
            assertThat(february.getNonCashSettled()).isEqualByComparingTo("600.00");
        }

        @Test
        @DisplayName("received is attributed to the ACTUAL clearedAt window, not any() (ADR-0057 Implementation"
                + " Notes / Testing)")
        void receivedPinnedToActualWindow() {
            stubWindowedFixture(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(
                            Map.entry(Instant.parse("2026-01-10T00:00:00Z"), "500.00"),
                            Map.entry(Instant.parse("2026-02-10T00:00:00Z"), "700.00")),
                    List.of());

            CollectionsAnalyticsReport january =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
            CollectionsAnalyticsReport february =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

            assertThat(january.getReceived()).isEqualByComparingTo("500.00");
            assertThat(february.getReceived()).isEqualByComparingTo("700.00");
        }

        @Test
        @DisplayName("customer-credit typed sums (APPLICATION → nonCashSettled, REFUND → refunded) are"
                + " attributed to the ACTUAL createdAt window AND filtered by type, not any() (ADR-0057"
                + " Implementation Notes / Testing)")
        void customerCreditTypedSumsPinnedToActualWindowAndType() {
            stubWindowedFixture(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(
                            new CreditTxnFixture(
                                    CustomerCreditTransactionType.APPLICATION,
                                    Instant.parse("2026-01-10T00:00:00Z"),
                                    new BigDecimal("150.00")),
                            new CreditTxnFixture(
                                    CustomerCreditTransactionType.REFUND,
                                    Instant.parse("2026-01-12T00:00:00Z"),
                                    new BigDecimal("90.00")),
                            new CreditTxnFixture(
                                    CustomerCreditTransactionType.APPLICATION,
                                    Instant.parse("2026-02-10T00:00:00Z"),
                                    new BigDecimal("250.00")),
                            new CreditTxnFixture(
                                    CustomerCreditTransactionType.REFUND,
                                    Instant.parse("2026-02-12T00:00:00Z"),
                                    new BigDecimal("40.00"))));

            CollectionsAnalyticsReport january =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
            CollectionsAnalyticsReport february =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

            assertThat(january.getNonCashSettled()).isEqualByComparingTo("150.00");
            assertThat(january.getRefunded()).isEqualByComparingTo("90.00");
            assertThat(february.getNonCashSettled()).isEqualByComparingTo("250.00");
            assertThat(february.getRefunded()).isEqualByComparingTo("40.00");
        }

        @Test
        @DisplayName("ADR-0057 Implementation Notes / Testing narrative: deposit taken and cleared in one"
                + " window, drawn down against the settlement invoice in a LATER window")
        void depositTakeCashInOneWindowDrawDownInALaterWindow() {
            // The deposit-take invoice itself finalizes alongside the cash clearing in window A;
            // the down payment is drawn down against the settlement invoice's balance in window B.
            // window A must show the cash coming IN (received) with no settlement yet; window B
            // must show the settlement (nonCashSettled) with no new cash (collected/received both 0).
            UUID depositInvoiceId = UUID.randomUUID();
            // The down payment becomes a pos-invoice DepositCredit, so the window-B draw-down
            // arrives through the deposit-credit replica leg, not the customer-credit subledger.
            stubWindowedFixture(
                    List.of(depositTakeInvoice(depositInvoiceId, Instant.parse("2026-01-05T00:00:00Z"), "500.00")),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(Map.entry(Instant.parse("2026-02-05T00:00:00Z"), "500.00")),
                    List.of(Map.entry(Instant.parse("2026-01-05T00:00:00Z"), "500.00")),
                    List.of());

            CollectionsAnalyticsReport january =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
            CollectionsAnalyticsReport february =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

            assertThat(january.getReceived()).isEqualByComparingTo("500.00");
            assertThat(january.getNonCashSettled()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(january.getInvoiced())
                    .as("the deposit-take document is a contract liability, never invoiced revenue (D6/D8)")
                    .isEqualByComparingTo(BigDecimal.ZERO);

            assertThat(february.getNonCashSettled()).isEqualByComparingTo("500.00");
            assertThat(february.getCollected()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(february.getReceived()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("A heavy-reversal window drives collected negative and it is NOT clamped to zero")
        void heavyReversalWindowGoesNegativeAndIsNotClamped() {
            when(extInvoiceRepository.findByFinalizedAtBetween(any(), any()))
                    .thenReturn(List.of(invoice(UUID.randomUUID(), Instant.parse("2026-06-02T00:00:00Z"), "1000.00")));
            when(paymentApplicationRepository.findByApplicationTimestampBetween(any(), any()))
                    .thenReturn(List.of(
                            application(UUID.randomUUID(), Instant.parse("2026-06-10T00:00:00Z"), "100.00", "900.00")));
            when(paymentApplicationReversalRepository.sumAmountByReversedAtBetween(any(), any()))
                    .thenReturn(new BigDecimal("900.00"));

            CollectionsAnalyticsReport report =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

            assertThat(report.getCollected()).isNegative();
            assertThat(report.getCollected()).isEqualByComparingTo("-800.00");
            // Gross, and positive, even while collected is negative.
            assertThat(report.getApplicationReversals()).isPositive();
            assertThat(report.getApplicationReversals()).isEqualByComparingTo("900.00");
            assertThat(report.getCollectionRatePct()).isEqualByComparingTo("-80.00");
        }

        @Test
        @DisplayName("A customer-credit draw-down in the window contributes ZERO to collected, but counts in"
                + " nonCashSettled (ADR-0057, #1621)")
        void customerCreditDrawDownContributesNothingToCollected() {
            // ADR-0057 names this as an acceptance condition: a deposit/customer-credit draw-down
            // relieves A/R but is NOT cash collected in the window, so it must contribute to
            // `collected` exactly once and only in the take window — i.e. never here.
            //
            // The exclusion holds STRUCTURALLY: applying a customer credit writes a
            // CustomerCreditTransaction (CustomerCreditServiceImpl#applyCreditToInvoice) and never a
            // PaymentApplication, and InvoiceBalanceCalculator#balanceDue keeps `creditApplied` as a
            // separate term from `applied`.
            //
            // Since #1621 the service legitimately depends on CustomerCreditTransactionRepository to
            // compute `nonCashSettled`, so the old reflection-based "must hold no CustomerCredit field"
            // tripwire no longer applies — the separation is now pinned on the values instead: the
            // draw-down must land in nonCashSettled and nowhere near collected.
            CustomerCreditTransaction drawDown = CustomerCreditTransaction.builder()
                    .creditTransactionId(UUID.randomUUID())
                    .creditId(UUID.randomUUID())
                    .transactionType(CustomerCreditTransactionType.APPLICATION)
                    .invoiceId(UUID.randomUUID())
                    .amount(new BigDecimal("750.00"))
                    .currency("USD")
                    .requestId(UUID.randomUUID().toString())
                    .createdAt(Instant.parse("2026-06-12T00:00:00Z")) // inside the reported window
                    .createdBy("test")
                    .build();

            when(extInvoiceRepository.findByFinalizedAtBetween(any(), any()))
                    .thenReturn(List.of(invoice(UUID.randomUUID(), Instant.parse("2026-06-02T00:00:00Z"), "1000.00")));
            when(paymentApplicationRepository.findByApplicationTimestampBetween(any(), any()))
                    .thenReturn(List.of(
                            application(UUID.randomUUID(), Instant.parse("2026-06-10T00:00:00Z"), "300.00", "0.00")));
            when(paymentApplicationReversalRepository.sumAmountByReversedAtBetween(any(), any()))
                    .thenReturn(BigDecimal.ZERO);
            when(customerCreditTransactionRepository.sumAmountByTypeAndCreatedAtBetween(
                            eq(CustomerCreditTransactionType.APPLICATION), any(), any()))
                    .thenReturn(drawDown.getAmount());
            when(customerCreditTransactionRepository.sumAmountByTypeAndCreatedAtBetween(
                            eq(CustomerCreditTransactionType.REFUND), any(), any()))
                    .thenReturn(BigDecimal.ZERO);

            CollectionsAnalyticsReport report =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

            // Only the cash application counts toward collected. Had the draw-down been folded in,
            // collected would read 1050.00 and the rate 105.00% — an invoice "over-collected" without
            // cash moving.
            assertThat(report.getCollected()).isEqualByComparingTo("300.00");
            assertThat(report.getCollected())
                    .as("credit draw-down of %s must not be added to collected", drawDown.getAmount())
                    .isNotEqualByComparingTo(new BigDecimal("300.00").add(drawDown.getAmount()));
            assertThat(report.getCollectionRatePct()).isEqualByComparingTo("30.00");

            // The draw-down instead lands in nonCashSettled (#1621), not collected.
            assertThat(report.getNonCashSettled()).isEqualByComparingTo("750.00");
            assertThat(report.getSettled()).isEqualByComparingTo("1050.00");
        }

        @Test
        @DisplayName("collectionRatePct stays null when invoiced is zero even if reversals make collected negative")
        void nullRateWhenInvoicedIsZeroWithReversals() {
            when(extInvoiceRepository.findByFinalizedAtBetween(any(), any())).thenReturn(List.of());
            when(paymentApplicationRepository.findByApplicationTimestampBetween(any(), any()))
                    .thenReturn(List.of(
                            application(UUID.randomUUID(), Instant.parse("2026-06-10T00:00:00Z"), "300.00", "0.00")));
            when(paymentApplicationReversalRepository.sumAmountByReversedAtBetween(any(), any()))
                    .thenReturn(new BigDecimal("500.00"));

            CollectionsAnalyticsReport report =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

            assertThat(report.getInvoiced()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(report.getCollected()).isEqualByComparingTo("-200.00");
            assertThat(report.getApplicationReversals()).isEqualByComparingTo("500.00");
            assertThat(report.getCollectionRatePct()).isNull();
        }

        @Test
        @DisplayName("A refund in the window appears in refunded and does not alter collected (#1620 AC)")
        void refundAppearsInRefundedAndDoesNotAlterCollected() {
            when(extInvoiceRepository.findByFinalizedAtBetween(any(), any()))
                    .thenReturn(List.of(invoice(UUID.randomUUID(), Instant.parse("2026-06-05T00:00:00Z"), "1000.00")));
            when(paymentApplicationRepository.findByApplicationTimestampBetween(any(), any()))
                    .thenReturn(List.of(
                            application(UUID.randomUUID(), Instant.parse("2026-06-10T00:00:00Z"), "300.00", "0.00")));
            when(extInvoicePaymentReversalRepository.sumAmountByReversedAtBetween(any(), any()))
                    .thenReturn(new BigDecimal("450.00"));

            CollectionsAnalyticsReport report =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

            assertThat(report.getRefunded()).isEqualByComparingTo("450.00");
            assertThat(report.getCollected()).isEqualByComparingTo("300.00");
        }

        @Test
        @DisplayName("refunded sums BOTH the pos-invoice replica and the accounting credit-balance"
                + " REFUND feed (ADR-0057 §4)")
        void refundedSumsBothSources() {
            // Same both-feeds shape as nonCashSettledSumsBothSources: one source alone reads
            // complete while systematically short of the other, disjoint subledger.
            when(extInvoiceRepository.findByFinalizedAtBetween(any(), any())).thenReturn(List.of());
            when(paymentApplicationRepository.findByApplicationTimestampBetween(any(), any()))
                    .thenReturn(List.of());
            when(extInvoicePaymentReversalRepository.sumAmountByReversedAtBetween(any(), any()))
                    .thenReturn(new BigDecimal("450.00"));
            when(customerCreditTransactionRepository.sumAmountByTypeAndCreatedAtBetween(
                            eq(CustomerCreditTransactionType.REFUND), any(), any()))
                    .thenReturn(new BigDecimal("120.00"));
            when(customerCreditTransactionRepository.sumAmountByTypeAndCreatedAtBetween(
                            eq(CustomerCreditTransactionType.APPLICATION), any(), any()))
                    .thenReturn(BigDecimal.ZERO);

            CollectionsAnalyticsReport report =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

            assertThat(report.getRefunded()).isEqualByComparingTo("570.00");
            // Neither feed leaks into collected or received.
            assertThat(report.getCollected()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(report.getReceived()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("netCashCollected = collected - refunded, may go negative, and is NOT clamped (#1620)")
        void netCashCollectedMayGoNegativeAndIsNotClamped() {
            when(extInvoiceRepository.findByFinalizedAtBetween(any(), any()))
                    .thenReturn(List.of(invoice(UUID.randomUUID(), Instant.parse("2026-06-05T00:00:00Z"), "1000.00")));
            when(paymentApplicationRepository.findByApplicationTimestampBetween(any(), any()))
                    .thenReturn(List.of(
                            application(UUID.randomUUID(), Instant.parse("2026-06-10T00:00:00Z"), "200.00", "0.00")));
            when(extInvoicePaymentReversalRepository.sumAmountByReversedAtBetween(any(), any()))
                    .thenReturn(new BigDecimal("500.00"));

            CollectionsAnalyticsReport report =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

            assertThat(report.getCollected()).isEqualByComparingTo("200.00");
            assertThat(report.getRefunded()).isEqualByComparingTo("500.00");
            assertThat(report.getNetCashCollected()).isEqualByComparingTo("-300.00");
            assertThat(report.getNetCashCollected()).isNegative();
        }

        @Test
        @DisplayName("received flows from the clearedAt query and does not affect collected (#1622 AC)")
        void receivedFlowsFromClearedAtQueryAndDoesNotAffectCollected() {
            when(extInvoiceRepository.findByFinalizedAtBetween(any(), any())).thenReturn(List.of());
            when(paymentApplicationRepository.findByApplicationTimestampBetween(any(), any()))
                    .thenReturn(List.of());
            when(receivablePaymentRepository.sumTotalAmountByClearedAtBetween(any(), any()))
                    .thenReturn(new BigDecimal("700.00"));

            CollectionsAnalyticsReport report =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

            assertThat(report.getReceived()).isEqualByComparingTo("700.00");
            assertThat(report.getCollected()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Cash received in window 1 and applied in window 2 are independent: first shows"
                + " received>0/collected==0, second shows collected>0/received==0 (#1622 AC)")
        void receivedAndAppliedInDifferentWindowsAreIndependent() {
            Instant receivedAt = Instant.parse("2026-01-15T00:00:00Z");
            Instant appliedAt = Instant.parse("2026-02-10T00:00:00Z");
            UUID invoiceId = UUID.randomUUID();

            when(receivablePaymentRepository.sumTotalAmountByClearedAtBetween(any(), any()))
                    .thenAnswer(call -> within(receivedAt, call.getArgument(0), call.getArgument(1))
                            ? new BigDecimal("500.00")
                            : BigDecimal.ZERO);
            when(paymentApplicationRepository.findByApplicationTimestampBetween(any(), any()))
                    .thenAnswer(call -> within(appliedAt, call.getArgument(0), call.getArgument(1))
                            ? List.of(application(invoiceId, appliedAt, "500.00", "0.00"))
                            : List.of());

            CollectionsAnalyticsReport january =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
            CollectionsAnalyticsReport february =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

            assertThat(january.getReceived()).isEqualByComparingTo("500.00");
            assertThat(january.getCollected()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(february.getCollected()).isEqualByComparingTo("500.00");
            assertThat(february.getReceived()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Invoice settled entirely by deposit credit: nonCashSettled>0, collected==0,"
                + " settlementRatePct reaches 100.00 while collectionRatePct is 0 (#1621 AC)")
        void depositCreditOnlySettlementReachesFullSettlementRateWithZeroCollectionRate() {
            when(extInvoiceRepository.findByFinalizedAtBetween(any(), any()))
                    .thenReturn(List.of(invoice(UUID.randomUUID(), Instant.parse("2026-06-05T00:00:00Z"), "1000.00")));
            when(paymentApplicationRepository.findByApplicationTimestampBetween(any(), any()))
                    .thenReturn(List.of());
            when(extInvoiceDepositCreditApplicationRepository.sumAmountAppliedByAppliedAtBetween(any(), any()))
                    .thenReturn(new BigDecimal("1000.00"));

            CollectionsAnalyticsReport report =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

            assertThat(report.getCollected()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(report.getCollectionRatePct()).isEqualByComparingTo("0.00");
            assertThat(report.getNonCashSettled()).isEqualByComparingTo("1000.00");
            assertThat(report.getSettled()).isEqualByComparingTo("1000.00");
            assertThat(report.getSettlementRatePct()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("nonCashSettled sums BOTH the deposit-credit replica and the customer-credit"
                + " APPLICATION draw-downs (#1621)")
        void nonCashSettledSumsBothSources() {
            when(extInvoiceRepository.findByFinalizedAtBetween(any(), any())).thenReturn(List.of());
            when(paymentApplicationRepository.findByApplicationTimestampBetween(any(), any()))
                    .thenReturn(List.of());
            when(extInvoiceDepositCreditApplicationRepository.sumAmountAppliedByAppliedAtBetween(any(), any()))
                    .thenReturn(new BigDecimal("300.00"));
            when(customerCreditTransactionRepository.sumAmountByTypeAndCreatedAtBetween(
                            eq(CustomerCreditTransactionType.APPLICATION), any(), any()))
                    .thenReturn(new BigDecimal("200.00"));
            when(customerCreditTransactionRepository.sumAmountByTypeAndCreatedAtBetween(
                            eq(CustomerCreditTransactionType.REFUND), any(), any()))
                    .thenReturn(BigDecimal.ZERO);

            CollectionsAnalyticsReport report =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

            assertThat(report.getNonCashSettled()).isEqualByComparingTo("500.00");
        }

        @Test
        @DisplayName("settled = collected + nonCashSettled; settlementRatePct is null when invoiced is zero")
        void settledSumsCollectedAndNonCashSettledAndRateIsNullWhenInvoicedZero() {
            when(extInvoiceRepository.findByFinalizedAtBetween(any(), any())).thenReturn(List.of());
            when(paymentApplicationRepository.findByApplicationTimestampBetween(any(), any()))
                    .thenReturn(List.of(
                            application(UUID.randomUUID(), Instant.parse("2026-06-10T00:00:00Z"), "300.00", "0.00")));
            when(extInvoiceDepositCreditApplicationRepository.sumAmountAppliedByAppliedAtBetween(any(), any()))
                    .thenReturn(new BigDecimal("150.00"));

            CollectionsAnalyticsReport report =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

            assertThat(report.getCollected()).isEqualByComparingTo("300.00");
            assertThat(report.getNonCashSettled()).isEqualByComparingTo("150.00");
            assertThat(report.getSettled()).isEqualByComparingTo("450.00");
            assertThat(report.getSettlementRatePct()).isNull();
        }
    }

    @Nested
    @DisplayName("getPaymentLagCohorts (E3)")
    class PaymentLagCohortsTests {

        private final LocalDate issuedFrom = LocalDate.of(2026, 1, 1);
        private final LocalDate issuedTo = LocalDate.of(2026, 6, 30);

        @Test
        @DisplayName("Rejects issuedTo before issuedFrom")
        void rejectsInvalidRange() {
            assertThatThrownBy(() -> service.getPaymentLagCohorts(issuedTo, issuedFrom, 4))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Buckets by whole days to first zero balance, inclusive at the upper edge (30/60/90)")
        void bucketsByLagInclusiveBoundaries() {
            UUID paidIn30 = UUID.randomUUID();
            UUID paidIn31 = UUID.randomUUID();
            UUID paidIn60 = UUID.randomUUID();
            UUID paidIn61 = UUID.randomUUID();
            UUID paidIn90 = UUID.randomUUID();
            UUID paidIn91 = UUID.randomUUID();
            Instant issueDate = Instant.parse("2026-01-01T00:00:00Z");

            when(extInvoiceRepository.findByFinalizedAtBetween(any(), any()))
                    .thenReturn(List.of(
                            invoice(paidIn30, issueDate, "100.00"),
                            invoice(paidIn31, issueDate, "200.00"),
                            invoice(paidIn60, issueDate, "300.00"),
                            invoice(paidIn61, issueDate, "400.00"),
                            invoice(paidIn90, issueDate, "500.00"),
                            invoice(paidIn91, issueDate, "600.00")));
            when(paymentApplicationRepository.findByInvoiceIdIn(anyCollection()))
                    .thenReturn(List.of(
                            application(
                                    paidIn30, issueDate.plus(30, java.time.temporal.ChronoUnit.DAYS), "100.00", "0.00"),
                            application(
                                    paidIn31, issueDate.plus(31, java.time.temporal.ChronoUnit.DAYS), "200.00", "0.00"),
                            application(
                                    paidIn60, issueDate.plus(60, java.time.temporal.ChronoUnit.DAYS), "300.00", "0.00"),
                            application(
                                    paidIn61, issueDate.plus(61, java.time.temporal.ChronoUnit.DAYS), "400.00", "0.00"),
                            application(
                                    paidIn90, issueDate.plus(90, java.time.temporal.ChronoUnit.DAYS), "500.00", "0.00"),
                            application(
                                    paidIn91,
                                    issueDate.plus(91, java.time.temporal.ChronoUnit.DAYS),
                                    "600.00",
                                    "0.00")));

            PaymentLagCohortsReport report = service.getPaymentLagCohorts(issuedFrom, issuedTo, 4);

            Map<String, PaymentLagCohortRow> byCohort = byCohort(report);
            assertThat(byCohort.get("<=30").getInvoiceCount()).isEqualTo(1);
            assertThat(byCohort.get("<=30").getAmount()).isEqualByComparingTo("100.00");
            assertThat(byCohort.get("31-60").getInvoiceCount()).isEqualTo(2);
            assertThat(byCohort.get("31-60").getAmount()).isEqualByComparingTo("500.00");
            assertThat(byCohort.get("61-90").getInvoiceCount()).isEqualTo(2);
            assertThat(byCohort.get("61-90").getAmount()).isEqualByComparingTo("900.00");
            // paidIn91 exceeds the 90-day edge, so it lands in unpaid alongside never-paid invoices.
            assertThat(byCohort.get("unpaid").getInvoiceCount()).isEqualTo(1);
            assertThat(byCohort.get("unpaid").getAmount()).isEqualByComparingTo("600.00");
        }

        @Test
        @DisplayName("unpaid is a real cohort: an invoice with no application contributes its full amount")
        void noApplicationLandsInUnpaid() {
            UUID invoiceId = UUID.randomUUID();
            when(extInvoiceRepository.findByFinalizedAtBetween(any(), any()))
                    .thenReturn(List.of(invoice(invoiceId, Instant.parse("2026-01-01T00:00:00Z"), "750.00")));
            when(paymentApplicationRepository.findByInvoiceIdIn(anyCollection()))
                    .thenReturn(List.of());

            PaymentLagCohortsReport report = service.getPaymentLagCohorts(issuedFrom, issuedTo, 4);

            Map<String, PaymentLagCohortRow> byCohort = byCohort(report);
            assertThat(byCohort.get("unpaid").getInvoiceCount()).isEqualTo(1);
            assertThat(byCohort.get("unpaid").getAmount()).isEqualByComparingTo("750.00");
            assertThat(byCohort.get("<=30").getInvoiceCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Partially-applied invoice (balance never reaches zero) stays in unpaid with its full amount")
        void partiallyAppliedStaysUnpaid() {
            UUID invoiceId = UUID.randomUUID();
            Instant issueDate = Instant.parse("2026-01-01T00:00:00Z");
            when(extInvoiceRepository.findByFinalizedAtBetween(any(), any()))
                    .thenReturn(List.of(invoice(invoiceId, issueDate, "1000.00")));
            // Two partial applications; balance never hits zero within observed data.
            when(paymentApplicationRepository.findByInvoiceIdIn(anyCollection()))
                    .thenReturn(List.of(
                            application(
                                    invoiceId,
                                    issueDate.plus(5, java.time.temporal.ChronoUnit.DAYS),
                                    "400.00",
                                    "600.00"),
                            application(
                                    invoiceId,
                                    issueDate.plus(10, java.time.temporal.ChronoUnit.DAYS),
                                    "300.00",
                                    "300.00")));

            PaymentLagCohortsReport report = service.getPaymentLagCohorts(issuedFrom, issuedTo, 4);

            Map<String, PaymentLagCohortRow> byCohort = byCohort(report);
            assertThat(byCohort.get("unpaid").getInvoiceCount()).isEqualTo(1);
            // Full invoice total, not the remaining open balance.
            assertThat(byCohort.get("unpaid").getAmount()).isEqualByComparingTo("1000.00");
        }

        @Test
        @DisplayName("Multiple applications: only the first application to reach zero balance sets the lag")
        void usesFirstZeroBalanceApplication() {
            UUID invoiceId = UUID.randomUUID();
            Instant issueDate = Instant.parse("2026-01-01T00:00:00Z");
            when(extInvoiceRepository.findByFinalizedAtBetween(any(), any()))
                    .thenReturn(List.of(invoice(invoiceId, issueDate, "1000.00")));
            when(paymentApplicationRepository.findByInvoiceIdIn(anyCollection()))
                    .thenReturn(List.of(
                            application(
                                    invoiceId,
                                    issueDate.plus(10, java.time.temporal.ChronoUnit.DAYS),
                                    "1000.00",
                                    "0.00"),
                            // A later reversal-style application re-touches the invoice; must not overwrite the
                            // already-reached full-payment lag.
                            application(
                                    invoiceId,
                                    issueDate.plus(40, java.time.temporal.ChronoUnit.DAYS),
                                    "0.00",
                                    "0.00")));

            PaymentLagCohortsReport report = service.getPaymentLagCohorts(issuedFrom, issuedTo, 4);

            Map<String, PaymentLagCohortRow> byCohort = byCohort(report);
            assertThat(byCohort.get("<=30").getInvoiceCount()).isEqualTo(1);
            assertThat(byCohort.get("61-90").getInvoiceCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Always returns all four cohorts, zeroed, for an empty window")
        void returnsAllCohortsWhenEmpty() {
            when(extInvoiceRepository.findByFinalizedAtBetween(any(), any())).thenReturn(List.of());

            PaymentLagCohortsReport report = service.getPaymentLagCohorts(issuedFrom, issuedTo, 4);

            assertThat(report.getCohorts()).hasSize(4);
            assertThat(report.getCohorts())
                    .extracting(PaymentLagCohortRow::getCohort)
                    .containsExactly("<=30", "31-60", "61-90", "unpaid");
            report.getCohorts().forEach(row -> {
                assertThat(row.getInvoiceCount()).isEqualTo(0);
                assertThat(row.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            });
        }

        @Test
        @DisplayName("A non-positive limit falls back to the default of 4 (all cohorts)")
        void nonPositiveLimitDefaultsToFour() {
            when(extInvoiceRepository.findByFinalizedAtBetween(any(), any())).thenReturn(List.of());

            PaymentLagCohortsReport report = service.getPaymentLagCohorts(issuedFrom, issuedTo, 0);

            assertThat(report.getCohorts()).hasSize(4);
        }

        @Test
        @DisplayName("limit truncates the fixed-order row list and caps above 4 at 4")
        void limitTruncatesRows() {
            when(extInvoiceRepository.findByFinalizedAtBetween(any(), any())).thenReturn(List.of());

            PaymentLagCohortsReport truncated = service.getPaymentLagCohorts(issuedFrom, issuedTo, 2);
            assertThat(truncated.getCohorts())
                    .extracting(PaymentLagCohortRow::getCohort)
                    .containsExactly("<=30", "31-60");
            assertThat(truncated.isTruncated()).isTrue();

            PaymentLagCohortsReport overCapped = service.getPaymentLagCohorts(issuedFrom, issuedTo, 999);
            assertThat(overCapped.getCohorts()).hasSize(4);
            assertThat(overCapped.isTruncated()).isFalse();
        }

        private Map<String, PaymentLagCohortRow> byCohort(PaymentLagCohortsReport report) {
            return report.getCohorts().stream()
                    .collect(java.util.stream.Collectors.toMap(PaymentLagCohortRow::getCohort, row -> row));
        }
    }

    @Nested
    @DisplayName("getVendorSpend (E8)")
    class VendorSpendTests {

        private final LocalDate start = LocalDate.of(2026, 6, 1);
        private final LocalDate end = LocalDate.of(2026, 6, 30);

        @Test
        @DisplayName("Rejects endDate before startDate")
        void rejectsInvalidRange() {
            assertThatThrownBy(() -> service.getVendorSpend(end, start, 20))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Rejects a non-positive limit")
        void rejectsNonPositiveLimit() {
            assertThatThrownBy(() -> service.getVendorSpend(start, end, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName(
                "paidAmount (settled A/P cash) and billsIssuedInWindow/avgIssuedBillAmount are independent populations")
        void paidAmountAndBillsAreIndependentPopulations() {
            UUID vendorId = UUID.randomUUID();

            // Settled payment in the window, but no bill billed in this same window — proving the
            // payment-side and bill-side populations are independent (the payment can settle a
            // bill billed in an earlier window).
            when(apPaymentRepository.findByStatusInAndPaymentDateBetween(any(), any(), any()))
                    .thenReturn(List.of(
                            settledPayment(vendorId, "Acme Parts Co", LocalDateTime.of(2026, 6, 10, 0, 0), "1000.00")));
            when(vendorBillRepository.findByBillDateBetween(any(), any())).thenReturn(List.of());
            when(vendorRepository.findAllById(any())).thenReturn(List.of());

            VendorSpendReport report = service.getVendorSpend(start, end, 20);

            assertThat(report.getRows()).hasSize(1);
            VendorSpendRow row = report.getRows().get(0);
            assertThat(row.getVendorId()).isEqualTo(vendorId);
            assertThat(row.getPaidAmount()).isEqualByComparingTo("1000.00");
            assertThat(row.getBillsIssuedInWindow()).isZero();
            // 0, never null, when billsIssuedInWindow is 0.
            assertThat(row.getAvgIssuedBillAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            // No directory entry: falls back to the vendor-name snapshot on the payment.
            assertThat(row.getName()).isEqualTo("Acme Parts Co");
        }

        @Test
        @DisplayName("Excludes payments that never moved cash (INITIATED/GATEWAY_PENDING/GATEWAY_FAILED)")
        void excludesUnsettledPaymentStatuses() {
            UUID vendorId = UUID.randomUUID();
            when(apPaymentRepository.findByStatusInAndPaymentDateBetween(any(), any(), any()))
                    .thenReturn(List.of());
            when(vendorBillRepository.findByBillDateBetween(any(), any()))
                    .thenReturn(
                            List.of(bill(vendorId, "Acme Parts Co", LocalDateTime.of(2026, 6, 15, 0, 0), "500.00")));
            when(vendorRepository.findAllById(any())).thenReturn(List.of());

            VendorSpendReport report = service.getVendorSpend(start, end, 20);

            assertThat(report.getRows()).hasSize(1);
            assertThat(report.getRows().get(0).getPaidAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(report.getRows().get(0).getBillsIssuedInWindow()).isEqualTo(1);
            assertThat(report.getRows().get(0).getAvgIssuedBillAmount()).isEqualByComparingTo("500.00");
        }

        @Test
        @DisplayName("Vendor directory name takes precedence over the payment/bill snapshot name")
        void directoryNameTakesPrecedence() {
            UUID vendorId = UUID.randomUUID();
            Vendor vendor = new Vendor(vendorId, "Acme Parts Company (canonical)");
            when(apPaymentRepository.findByStatusInAndPaymentDateBetween(any(), any(), any()))
                    .thenReturn(List.of(settledPayment(
                            vendorId, "Acme Parts Co (stale)", LocalDateTime.of(2026, 6, 10, 0, 0), "1000.00")));
            when(vendorBillRepository.findByBillDateBetween(any(), any())).thenReturn(List.of());
            when(vendorRepository.findAllById(any())).thenReturn(List.of(vendor));

            VendorSpendReport report = service.getVendorSpend(start, end, 20);

            assertThat(report.getRows().get(0).getName()).isEqualTo("Acme Parts Company (canonical)");
        }

        @Test
        @DisplayName("Orders rows by paidAmount descending and signals truncation via the limit")
        void ordersByPaidAmountDescendingAndTruncates() {
            UUID highVendor = UUID.randomUUID();
            UUID lowVendor = UUID.randomUUID();
            when(apPaymentRepository.findByStatusInAndPaymentDateBetween(any(), any(), any()))
                    .thenReturn(List.of(
                            settledPayment(lowVendor, "Low Vendor", LocalDateTime.of(2026, 6, 10, 0, 0), "100.00"),
                            settledPayment(highVendor, "High Vendor", LocalDateTime.of(2026, 6, 11, 0, 0), "9000.00")));
            when(vendorBillRepository.findByBillDateBetween(any(), any())).thenReturn(List.of());
            when(vendorRepository.findAllById(any())).thenReturn(List.of());

            VendorSpendReport full = service.getVendorSpend(start, end, 20);
            assertThat(full.getRows()).extracting(VendorSpendRow::getVendorId).containsExactly(highVendor, lowVendor);
            assertThat(full.isTruncated()).isFalse();

            VendorSpendReport capped = service.getVendorSpend(start, end, 1);
            assertThat(capped.getRows()).extracting(VendorSpendRow::getVendorId).containsExactly(highVendor);
            assertThat(capped.isTruncated()).isTrue();
            assertThat(capped.getLimit()).isEqualTo(1);
        }

        @Test
        @DisplayName("Returns an empty row list for a window with no settled payments and no bills")
        void returnsEmptyRowsWhenNoActivity() {
            when(apPaymentRepository.findByStatusInAndPaymentDateBetween(any(), any(), any()))
                    .thenReturn(List.of());
            when(vendorBillRepository.findByBillDateBetween(any(), any())).thenReturn(List.of());

            VendorSpendReport report = service.getVendorSpend(start, end, 20);

            assertThat(report.getRows()).isEmpty();
            assertThat(report.isTruncated()).isFalse();
        }
    }
}
