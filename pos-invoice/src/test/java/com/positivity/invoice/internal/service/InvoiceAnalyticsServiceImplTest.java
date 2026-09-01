package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.invoice.internal.dto.InvoicingLagReport;
import com.positivity.invoice.internal.dto.InvoicingLagRow;
import com.positivity.invoice.internal.dto.RevenueByCustomerReport;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.invoice.internal.repository.InvoiceRepository.InvoicingLagPairProjection;
import com.positivity.invoice.internal.repository.InvoiceRepository.RevenueByCustomerProjection;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class InvoiceAnalyticsServiceImplTest {

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private CustomerReferenceService customerReferenceService;

    private InvoiceAnalyticsServiceImpl service;

    private final LocalDate startDate = LocalDate.parse("2026-06-01");
    private final LocalDate endDate = LocalDate.parse("2026-06-30");

    private InvoiceAnalyticsServiceImpl service() {
        return new InvoiceAnalyticsServiceImpl(invoiceRepository, customerReferenceService);
    }

    private static RevenueByCustomerProjection projection(
            String customerId, BigDecimal revenue, long invoiceCount, Instant lastInvoiceDate) {
        return new RevenueByCustomerProjection() {
            @Override
            public String getCustomerId() {
                return customerId;
            }

            @Override
            public BigDecimal getRevenue() {
                return revenue;
            }

            @Override
            public long getInvoiceCount() {
                return invoiceCount;
            }

            @Override
            public Instant getLastInvoiceDate() {
                return lastInvoiceDate;
            }
        };
    }

    private static InvoicingLagPairProjection pair(Instant invoiceCreatedAt, Instant workorderCreatedAt) {
        return new InvoicingLagPairProjection() {
            @Override
            public Instant getInvoiceCreatedAt() {
                return invoiceCreatedAt;
            }

            @Override
            public Instant getWorkorderCreatedAt() {
                return workorderCreatedAt;
            }
        };
    }

    // ==================== revenueByCustomer ====================

    @Test
    void computesAvgInvoiceValueServerSide_andResolvesCustomerName() {
        service = service();
        when(invoiceRepository.revenueByCustomer(any(), any(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(projection(
                        "018f0000-0000-7000-8000-0000000000aa",
                        new BigDecimal("300.0000"),
                        3,
                        Instant.parse("2026-06-20T00:00:00Z"))));
        when(customerReferenceService.resolveNames(anyCollection()))
                .thenReturn(Map.of("018f0000-0000-7000-8000-0000000000aa", "Acme Towing LLC"));

        RevenueByCustomerReport report = service.revenueByCustomer(startDate, endDate, 20);

        assertThat(report.getRows()).hasSize(1);
        var row = report.getRows().getFirst();
        assertThat(row.getCustomerId()).isEqualTo("018f0000-0000-7000-8000-0000000000aa");
        assertThat(row.getName()).isEqualTo("Acme Towing LLC");
        assertThat(row.getRevenue()).isEqualByComparingTo("300.0000");
        assertThat(row.getInvoiceCount()).isEqualTo(3);
        assertThat(row.getAvgInvoiceValue()).isEqualByComparingTo("100.0000");
        assertThat(row.getLastInvoiceDate()).isEqualTo(Instant.parse("2026-06-20T00:00:00Z"));
        assertThat(report.isTruncated()).isFalse();
        assertThat(report.getLimit()).isEqualTo(20);
    }

    @Test
    void nameIsNull_whenCustomerReferenceServiceCannotResolveIt() {
        service = service();
        when(invoiceRepository.revenueByCustomer(any(), any(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(projection(
                        "018f0000-0000-7000-8000-0000000000aa", new BigDecimal("100.0000"), 1, Instant.now())));
        when(customerReferenceService.resolveNames(anyCollection())).thenReturn(Map.of());

        RevenueByCustomerReport report = service.revenueByCustomer(startDate, endDate, 20);

        assertThat(report.getRows().getFirst().getName()).isNull();
    }

    @Test
    void requestsOneRowMoreThanLimit_andSignalsTruncationWithoutReturningTheExtraRow() {
        service = service();
        when(invoiceRepository.revenueByCustomer(any(), any(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(
                        projection("a", new BigDecimal("300.0000"), 1, Instant.now()),
                        projection("b", new BigDecimal("200.0000"), 1, Instant.now())));
        when(customerReferenceService.resolveNames(anyCollection())).thenReturn(Map.of());

        RevenueByCustomerReport report = service.revenueByCustomer(startDate, endDate, 1);

        assertThat(report.isTruncated()).isTrue();
        assertThat(report.getRows()).hasSize(1);
        assertThat(report.getRows().getFirst().getCustomerId()).isEqualTo("a");

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(invoiceRepository).revenueByCustomer(any(), any(), anyCollection(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue()).isEqualTo(PageRequest.of(0, 2));
    }

    @Test
    void notTruncated_whenResultFitsExactlyWithinLimit() {
        service = service();
        when(invoiceRepository.revenueByCustomer(any(), any(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(projection("a", new BigDecimal("100.0000"), 1, Instant.now())));
        when(customerReferenceService.resolveNames(anyCollection())).thenReturn(Map.of());

        RevenueByCustomerReport report = service.revenueByCustomer(startDate, endDate, 5);

        assertThat(report.isTruncated()).isFalse();
        assertThat(report.getRows()).hasSize(1);
    }

    @Test
    void rejectsEndDateBeforeStartDate() {
        service = service();
        assertThatThrownBy(() -> service.revenueByCustomer(endDate, startDate, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== invoicingLag ====================

    @Test
    void excludesPairsWithNullWorkorderCreatedAt_fromBothAverageAndCount() {
        service = service();
        Instant invoiceCreatedAt = Instant.parse("2026-06-15T00:00:00Z");
        when(invoiceRepository.invoicingLagPairs(any(), any()))
                .thenReturn(List.of(
                        pair(invoiceCreatedAt, Instant.parse("2026-06-10T00:00:00Z")), // 5 days
                        pair(invoiceCreatedAt, null))); // excluded

        InvoicingLagReport report = service.invoicingLag(startDate, endDate);

        InvoicingLagRow row = report.getRows().getFirst();
        assertThat(row.getCount()).isEqualTo(1);
        assertThat(row.getAvgDaysWoCreationToInvoice()).isEqualTo(5.0);
    }

    @Test
    void averagesAcrossMultipleQualifyingPairs() {
        service = service();
        Instant invoiceCreatedAt = Instant.parse("2026-06-15T00:00:00Z");
        when(invoiceRepository.invoicingLagPairs(any(), any()))
                .thenReturn(List.of(
                        pair(invoiceCreatedAt, invoiceCreatedAt.minusSeconds(2 * 86_400)), // 2 days
                        pair(invoiceCreatedAt, invoiceCreatedAt.minusSeconds(4 * 86_400)))); // 4 days

        InvoicingLagReport report = service.invoicingLag(startDate, endDate);

        InvoicingLagRow row = report.getRows().getFirst();
        assertThat(row.getCount()).isEqualTo(2);
        assertThat(row.getAvgDaysWoCreationToInvoice()).isEqualTo(3.0);
    }

    @Test
    void averageIsNull_notZero_whenNoQualifyingPairExists() {
        service = service();
        when(invoiceRepository.invoicingLagPairs(any(), any())).thenReturn(List.of());

        InvoicingLagReport report = service.invoicingLag(startDate, endDate);

        InvoicingLagRow row = report.getRows().getFirst();
        assertThat(row.getCount()).isEqualTo(0);
        assertThat(row.getAvgDaysWoCreationToInvoice()).isNull();
    }

    @Test
    void averageIsNull_whenEveryPairHasNoWorkorderCreatedAt() {
        service = service();
        Instant invoiceCreatedAt = Instant.parse("2026-06-15T00:00:00Z");
        when(invoiceRepository.invoicingLagPairs(any(), any()))
                .thenReturn(List.of(pair(invoiceCreatedAt, null), pair(invoiceCreatedAt, null)));

        InvoicingLagReport report = service.invoicingLag(startDate, endDate);

        InvoicingLagRow row = report.getRows().getFirst();
        assertThat(row.getCount()).isEqualTo(0);
        assertThat(row.getAvgDaysWoCreationToInvoice()).isNull();
    }

    @Test
    void invoicingLag_rejectsEndDateBeforeStartDate() {
        service = service();
        assertThatThrownBy(() -> service.invoicingLag(endDate, startDate)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invoicingLag_reportEchoesTheRequestedWindow_andAlwaysReturnsExactlyOneRow() {
        service = service();
        when(invoiceRepository.invoicingLagPairs(any(), any())).thenReturn(List.of());

        InvoicingLagReport report = service.invoicingLag(startDate, endDate);

        assertThat(report.getStartDate()).isEqualTo(startDate);
        assertThat(report.getEndDate()).isEqualTo(endDate);
        assertThat(report.getRows()).hasSize(1);
    }

    @Test
    void revenueByCustomerWindow_usesStartOfStartDateAndEndOfEndDateInUtc() {
        service = service();
        when(invoiceRepository.revenueByCustomer(any(), any(), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of());

        service.revenueByCustomer(startDate, endDate, 20);

        verify(invoiceRepository)
                .revenueByCustomer(
                        eq(Instant.parse("2026-06-01T00:00:00Z")),
                        eq(Instant.parse("2026-06-30T23:59:59.999999999Z")),
                        anyCollection(),
                        any(Pageable.class));
    }
}
