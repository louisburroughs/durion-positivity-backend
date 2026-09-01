package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.CollectionsAnalyticsReport;
import com.positivity.accounting.internal.dto.PaymentLagCohortRow;
import com.positivity.accounting.internal.dto.PaymentLagCohortsReport;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.PaymentApplication;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
 * the zero-invoiced ratio edge case (E2), and the payment-lag boundary / unpaid / partial-payment
 * cohort rules (E3).
 */
@ExtendWith(MockitoExtension.class)
class AccountingAnalyticsServiceImplTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ExtInvoiceRepository extInvoiceRepository;

    @Mock
    private PaymentApplicationRepository paymentApplicationRepository;

    private AccountingAnalyticsServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AccountingAnalyticsServiceImpl(TEST_CLOCK, extInvoiceRepository, paymentApplicationRepository);
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

            CollectionsAnalyticsReport report = service.getCollectionsAnalytics(start, end);

            assertThat(report.getInvoiced()).isEqualByComparingTo("1500.00");
            assertThat(report.getCollected()).isEqualByComparingTo("1200.00");
            assertThat(report.getCollectionRatePct()).isEqualByComparingTo("80.00");
            assertThat(report.getGeneratedAt()).isEqualTo(TEST_CLOCK.instant());
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

            CollectionsAnalyticsReport report =
                    service.getCollectionsAnalytics(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

            assertThat(report.getInvoiced()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(report.getCollected()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(report.getCollectionRatePct()).isNull();
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
}
