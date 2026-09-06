package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderLineRollup;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderStatusRollup;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderStatusSummary;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderSummaryResponse;
import com.positivity.order.internal.enums.PurchaseOrderStatus;
import com.positivity.order.internal.repository.PurchaseOrderLineRepository;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import com.positivity.order.internal.repository.PurchaseOrderTransmissionEventRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * #1798: a paged list cannot answer "how many units are on order". The summary sums the whole
 * population per status and lets a caller pick the statuses that mean "open".
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PurchaseOrderServiceImpl.summarizePurchaseOrders")
class PurchaseOrderSummaryTest {

    private static final UUID VENDOR_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02");

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private PurchaseOrderLineRepository purchaseOrderLineRepository;

    @Mock
    private PurchaseOrderTransmissionEventRepository transmissionEventRepository;

    @Mock
    private PurchaseOrderFactPublisher purchaseOrderFactPublisher;

    @Mock
    private DocumentQuantityConverter documentQuantityConverter;

    @Mock
    private jakarta.persistence.EntityManager entityManager;

    private PurchaseOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PurchaseOrderServiceImpl(
                purchaseOrderRepository,
                purchaseOrderLineRepository,
                transmissionEventRepository,
                entityManager,
                purchaseOrderFactPublisher,
                documentQuantityConverter,
                Clock.fixed(Instant.parse("2026-09-05T12:00:00Z"), ZoneOffset.UTC));
    }

    private void population() {
        when(purchaseOrderRepository.rollupByStatus())
                .thenReturn(List.of(
                        new PurchaseOrderStatusRollup(PurchaseOrderStatus.FULLY_RECEIVED, 2L, 50_000L, 0L),
                        new PurchaseOrderStatusRollup(PurchaseOrderStatus.APPROVED, 3L, 100_000L, 40_000L)));
        when(purchaseOrderLineRepository.rollupByStatus())
                .thenReturn(List.of(
                        new PurchaseOrderLineRollup(
                                PurchaseOrderStatus.APPROVED, 5L, new BigDecimal("100"), new BigDecimal("40")),
                        new PurchaseOrderLineRollup(
                                PurchaseOrderStatus.FULLY_RECEIVED, 3L, new BigDecimal("50"), BigDecimal.ZERO)));
    }

    @Test
    @DisplayName("with no filter, totals span every status and byStatus is in status order")
    void unfilteredTotalsSpanEveryStatus() {
        population();

        PurchaseOrderSummaryResponse summary = service.summarizePurchaseOrders(null, null);

        assertThat(summary.getOrderCount()).isEqualTo(5);
        assertThat(summary.getLineCount()).isEqualTo(8);
        assertThat(summary.getUnitsOrdered()).isEqualByComparingTo("150");
        assertThat(summary.getUnitsOpen()).isEqualByComparingTo("40");
        assertThat(summary.getUnitsReceived()).isEqualByComparingTo("110");
        assertThat(summary.getGrandTotalMinor()).isEqualTo(150_000L);
        assertThat(summary.getOpenBalanceMinor()).isEqualTo(40_000L);
        assertThat(summary.getByStatus())
                .extracting(PurchaseOrderStatusSummary::status)
                .containsExactly(PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.FULLY_RECEIVED);
        assertThat(summary.getStatus()).isNull();
        assertThat(summary.getVendorId()).isNull();
    }

    @Test
    @DisplayName("a status filter keeps only that status, and the totals follow")
    void statusFilterNarrowsTheTotals() {
        // The q24 trap: units ordered (100) and units open (40) are both plausible answers to "how
        // many units are still on order"; only the open figure is right.
        population();

        PurchaseOrderSummaryResponse summary = service.summarizePurchaseOrders(null, PurchaseOrderStatus.APPROVED);

        assertThat(summary.getStatus()).isEqualTo(PurchaseOrderStatus.APPROVED);
        assertThat(summary.getOrderCount()).isEqualTo(3);
        assertThat(summary.getUnitsOrdered()).isEqualByComparingTo("100");
        assertThat(summary.getUnitsOpen()).isEqualByComparingTo("40");
        assertThat(summary.getUnitsReceived()).isEqualByComparingTo("60");
        assertThat(summary.getByStatus()).hasSize(1);
    }

    @Test
    @DisplayName("a vendor filter uses the vendor-scoped roll-ups and never the population ones")
    void vendorFilterScopesTheQueries() {
        when(purchaseOrderRepository.rollupByStatusForVendor(VENDOR_ID))
                .thenReturn(List.of(new PurchaseOrderStatusRollup(PurchaseOrderStatus.APPROVED, 1L, 9_000L, 9_000L)));
        when(purchaseOrderLineRepository.rollupByStatusForVendor(VENDOR_ID))
                .thenReturn(List.of(new PurchaseOrderLineRollup(
                        PurchaseOrderStatus.APPROVED, 2L, new BigDecimal("12"), new BigDecimal("12"))));

        PurchaseOrderSummaryResponse summary = service.summarizePurchaseOrders(VENDOR_ID, null);

        assertThat(summary.getVendorId()).isEqualTo(VENDOR_ID);
        assertThat(summary.getUnitsOpen()).isEqualByComparingTo("12");
        verify(purchaseOrderRepository, never()).rollupByStatus();
        verify(purchaseOrderLineRepository, never()).rollupByStatus();
    }

    @Test
    @DisplayName("null sums and a status with orders but no lines read as zero, not as a crash")
    void nullSumsAreZero() {
        when(purchaseOrderRepository.rollupByStatus())
                .thenReturn(List.of(new PurchaseOrderStatusRollup(PurchaseOrderStatus.DRAFT, 4L, null, null)));
        when(purchaseOrderLineRepository.rollupByStatus()).thenReturn(List.of());

        PurchaseOrderSummaryResponse summary = service.summarizePurchaseOrders(null, null);

        assertThat(summary.getOrderCount()).isEqualTo(4);
        assertThat(summary.getLineCount()).isZero();
        assertThat(summary.getUnitsOrdered()).isEqualByComparingTo("0");
        assertThat(summary.getUnitsOpen()).isEqualByComparingTo("0");
        assertThat(summary.getGrandTotalMinor()).isZero();
    }

    @Test
    @DisplayName("an empty population answers zero totals and an empty breakdown")
    void emptyPopulation() {
        when(purchaseOrderRepository.rollupByStatus()).thenReturn(List.of());
        when(purchaseOrderLineRepository.rollupByStatus()).thenReturn(List.of());

        PurchaseOrderSummaryResponse summary = service.summarizePurchaseOrders(null, PurchaseOrderStatus.CANCELLED);

        assertThat(summary.getOrderCount()).isZero();
        assertThat(summary.getUnitsOpen()).isEqualByComparingTo("0");
        assertThat(summary.getByStatus()).isEmpty();
        verify(documentQuantityConverter, never()).convertIfPresent(any(), any(), any(), any());
    }
}
