package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.PaymentApplicationListRow;
import com.positivity.accounting.internal.entity.PaymentApplication;
import com.positivity.accounting.internal.entity.ReceivablePayment;
import com.positivity.accounting.internal.repository.PaymentApplicationRepository;
import com.positivity.accounting.internal.repository.PaymentApplicationReversalRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Unit tests for {@link PaymentApplicationQueryServiceImpl} (Wave 2 E10, issue #1598): window
 * validation, the default reversed-exclusion behavior, includeReversed flagging, and the
 * server-controlled sort/page-size cap.
 */
@ExtendWith(MockitoExtension.class)
class PaymentApplicationQueryServiceImplTest {

    @Mock
    private PaymentApplicationRepository paymentApplicationRepository;

    @Mock
    private PaymentApplicationReversalRepository paymentApplicationReversalRepository;

    private PaymentApplicationQueryServiceImpl service;

    private final LocalDate appliedFrom = LocalDate.of(2026, 6, 1);
    private final LocalDate appliedTo = LocalDate.of(2026, 6, 30);

    @BeforeEach
    void setUp() {
        service = new PaymentApplicationQueryServiceImpl(
                paymentApplicationRepository, paymentApplicationReversalRepository);
    }

    private static PaymentApplication application(UUID paymentId, UUID invoiceId, Instant appliedAt, String amount) {
        ReceivablePayment payment = new ReceivablePayment();
        payment.setPaymentId(paymentId);

        PaymentApplication app = new PaymentApplication();
        app.setPaymentApplicationId(UUID.randomUUID());
        app.setPayment(payment);
        app.setInvoiceId(invoiceId);
        app.setCustomerId(UUID.randomUUID());
        app.setCurrency("USD");
        app.setAppliedAmount(new BigDecimal(amount));
        app.setApplicationTimestamp(appliedAt);
        app.setApplicationRequestId(UUID.randomUUID().toString());
        app.setCreatedBy("test");
        return app;
    }

    @Nested
    @DisplayName("window validation")
    class WindowValidationTests {

        @Test
        @DisplayName("Rejects appliedTo before appliedFrom")
        void rejectsInvalidRange() {
            assertThatThrownBy(
                            () -> service.listByAppliedDateWindow(appliedTo, appliedFrom, false, PageRequest.of(0, 20)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Rejects a window wider than 366 days")
        void rejectsWindowTooWide() {
            LocalDate wideTo = appliedFrom.plusDays(400);

            assertThatThrownBy(() -> service.listByAppliedDateWindow(appliedFrom, wideTo, false, PageRequest.of(0, 20)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Accepts a window of exactly 366 days")
        void acceptsMaxWindow() {
            LocalDate maxTo = appliedFrom.plusDays(366);
            when(paymentApplicationRepository.findActiveByApplicationTimestampBetween(any(), any(), any()))
                    .thenReturn(Page.empty());

            service.listByAppliedDateWindow(appliedFrom, maxTo, false, PageRequest.of(0, 20));

            verify(paymentApplicationRepository).findActiveByApplicationTimestampBetween(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("includeReversed=false (default)")
    class DefaultExclusionTests {

        @Test
        @DisplayName("Delegates to the active-only (NOT EXISTS reversal) query and maps reversed=false")
        void delegatesToActiveOnlyQuery() {
            PaymentApplication app =
                    application(UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-06-15T10:00:00Z"), "450.00");
            when(paymentApplicationRepository.findActiveByApplicationTimestampBetween(any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(app)));

            Page<PaymentApplicationListRow> result =
                    service.listByAppliedDateWindow(appliedFrom, appliedTo, false, PageRequest.of(0, 20));

            assertThat(result.getContent()).hasSize(1);
            PaymentApplicationListRow row = result.getContent().get(0);
            assertThat(row.getApplicationId()).isEqualTo(app.getPaymentApplicationId());
            assertThat(row.getPaymentId()).isEqualTo(app.getPaymentId());
            assertThat(row.getInvoiceId()).isEqualTo(app.getInvoiceId());
            assertThat(row.getAmount()).isEqualByComparingTo("450.00");
            assertThat(row.isReversed()).isFalse();
            verify(paymentApplicationRepository, never()).findByApplicationTimestampBetween(any(), any(), any());
            verify(paymentApplicationReversalRepository, never()).findReversedApplicationIds(anyCollection());
        }
    }

    @Nested
    @DisplayName("includeReversed=true")
    class IncludeReversedTests {

        @Test
        @DisplayName("Includes reversed applications and flags reversed=true only for those with a reversal")
        void flagsReversedRowsOnly() {
            PaymentApplication active =
                    application(UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-06-10T10:00:00Z"), "100.00");
            PaymentApplication reversed =
                    application(UUID.randomUUID(), UUID.randomUUID(), Instant.parse("2026-06-11T10:00:00Z"), "200.00");
            when(paymentApplicationRepository.findByApplicationTimestampBetween(any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(active, reversed)));
            when(paymentApplicationReversalRepository.findReversedApplicationIds(anyCollection()))
                    .thenReturn(List.of(reversed.getPaymentApplicationId()));

            Page<PaymentApplicationListRow> result =
                    service.listByAppliedDateWindow(appliedFrom, appliedTo, true, PageRequest.of(0, 20));

            assertThat(result.getContent()).hasSize(2);
            PaymentApplicationListRow activeRow = result.getContent().stream()
                    .filter(row -> row.getApplicationId().equals(active.getPaymentApplicationId()))
                    .findFirst()
                    .orElseThrow();
            PaymentApplicationListRow reversedRow = result.getContent().stream()
                    .filter(row -> row.getApplicationId().equals(reversed.getPaymentApplicationId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(activeRow.isReversed()).isFalse();
            assertThat(reversedRow.isReversed()).isTrue();
            verify(paymentApplicationRepository, never()).findActiveByApplicationTimestampBetween(any(), any(), any());
        }

        @Test
        @DisplayName("Skips the reversal lookup entirely for an empty page")
        void skipsReversalLookupWhenEmpty() {
            when(paymentApplicationRepository.findByApplicationTimestampBetween(any(), any(), any()))
                    .thenReturn(Page.empty());

            service.listByAppliedDateWindow(appliedFrom, appliedTo, true, PageRequest.of(0, 20));

            verify(paymentApplicationReversalRepository, never()).findReversedApplicationIds(anyCollection());
        }
    }

    @Nested
    @DisplayName("paging/sort")
    class PagingTests {

        @Test
        @DisplayName("Caps the effective page size at the module's hard cap regardless of the requested size")
        void capsPageSize() {
            when(paymentApplicationRepository.findActiveByApplicationTimestampBetween(any(), any(), any()))
                    .thenReturn(Page.empty());

            service.listByAppliedDateWindow(appliedFrom, appliedTo, false, PageRequest.of(0, 10_000));

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(paymentApplicationRepository)
                    .findActiveByApplicationTimestampBetween(any(), any(), pageableCaptor.capture());
            assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
        }

        @Test
        @DisplayName("Ignores any caller-supplied sort and enforces appliedAt ascending server-side")
        void ignoresCallerSuppliedSort() {
            when(paymentApplicationRepository.findActiveByApplicationTimestampBetween(any(), any(), any()))
                    .thenReturn(Page.empty());

            service.listByAppliedDateWindow(
                    appliedFrom, appliedTo, false, PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "amount")));

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            verify(paymentApplicationRepository)
                    .findActiveByApplicationTimestampBetween(any(), any(), pageableCaptor.capture());
            Sort.Order order = pageableCaptor.getValue().getSort().getOrderFor("applicationTimestamp");
            assertThat(order).isNotNull();
            assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
        }
    }
}
