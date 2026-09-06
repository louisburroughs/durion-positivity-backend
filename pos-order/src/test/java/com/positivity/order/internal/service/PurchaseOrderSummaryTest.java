package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
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
 * population per status; without a status filter that population is incoming supply, because a
 * cancelled line keeps its open quantity and a status-blind sum would call it "on order".
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

    /**
     * Shaped like alpha on 2026-09-05: cancellation does not zero a line's open quantity, so the
     * CANCELLED rows carry more "open" units than the orders that are actually outstanding.
     */
    private void population() {
        when(purchaseOrderRepository.rollupByStatus())
                .thenReturn(List.of(
                        new PurchaseOrderStatusRollup(PurchaseOrderStatus.CANCELLED, 90L, 700_000L, 700_000L),
                        new PurchaseOrderStatusRollup(PurchaseOrderStatus.FULLY_RECEIVED, 2L, 50_000L, 0L),
                        new PurchaseOrderStatusRollup(PurchaseOrderStatus.DRAFT, 1L, 9_000L, 9_000L),
                        new PurchaseOrderStatusRollup(PurchaseOrderStatus.APPROVED, 3L, 100_000L, 40_000L)));
        when(purchaseOrderLineRepository.rollupByStatus())
                .thenReturn(List.of(
                        new PurchaseOrderLineRollup(
                                PurchaseOrderStatus.APPROVED, 5L, new BigDecimal("100"), new BigDecimal("40")),
                        new PurchaseOrderLineRollup(
                                PurchaseOrderStatus.CANCELLED, 200L, new BigDecimal("3450"), new BigDecimal("3450")),
                        new PurchaseOrderLineRollup(
                                PurchaseOrderStatus.DRAFT, 2L, new BigDecimal("70"), new BigDecimal("70")),
                        new PurchaseOrderLineRollup(
                                PurchaseOrderStatus.FULLY_RECEIVED, 3L, new BigDecimal("50"), BigDecimal.ZERO)));
    }

    @Test
    @DisplayName(
            "with no status filter, the population is incoming supply — cancelled and draft open units are not on order")
    void defaultPopulationIsIncomingSupply() {
        // #1798 review: a status-blind sum would have answered q24 with 40 + 3450 + 70 open units.
        population();

        PurchaseOrderSummaryResponse summary = service.summarizePurchaseOrders(null, null);

        assertThat(summary.getStatuses())
                .containsExactly(PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(summary.getOrderCount()).isEqualTo(3);
        assertThat(summary.getLineCount()).isEqualTo(5);
        assertThat(summary.getUnitsOrdered()).isEqualByComparingTo("100");
        assertThat(summary.getUnitsOpen()).isEqualByComparingTo("40");
        assertThat(summary.getUnitsReceived()).isEqualByComparingTo("60");
        assertThat(summary.getGrandTotalMinor()).isEqualTo(100_000L);
        assertThat(summary.getOpenBalanceMinor()).isEqualTo(40_000L);
        assertThat(summary.getByStatus())
                .extracting(PurchaseOrderStatusSummary::status)
                .containsExactly(PurchaseOrderStatus.APPROVED);
        assertThat(summary.getVendorId()).isNull();
    }

    @Test
    @DisplayName("an empty status list means the same as none")
    void emptyStatusListIsTheDefault() {
        population();

        assertThat(service.summarizePurchaseOrders(null, List.of()).getUnitsOpen())
                .isEqualByComparingTo("40");
    }

    @Test
    @DisplayName("named statuses widen the population, totals follow, and byStatus is in status order")
    void namedStatusesWidenThePopulation() {
        population();

        PurchaseOrderSummaryResponse summary = service.summarizePurchaseOrders(
                null, List.of(PurchaseOrderStatus.FULLY_RECEIVED, PurchaseOrderStatus.APPROVED));

        assertThat(summary.getStatuses())
                .containsExactly(PurchaseOrderStatus.FULLY_RECEIVED, PurchaseOrderStatus.APPROVED);
        assertThat(summary.getOrderCount()).isEqualTo(5);
        assertThat(summary.getLineCount()).isEqualTo(8);
        assertThat(summary.getUnitsOrdered()).isEqualByComparingTo("150");
        assertThat(summary.getUnitsOpen()).isEqualByComparingTo("40");
        assertThat(summary.getUnitsReceived()).isEqualByComparingTo("110");
        assertThat(summary.getGrandTotalMinor()).isEqualTo(150_000L);
        assertThat(summary.getByStatus())
                .extracting(PurchaseOrderStatusSummary::status)
                .containsExactly(PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.FULLY_RECEIVED);
    }

    @Test
    @DisplayName("asking for CANCELLED shows its open units, labelled by status, and nothing else")
    void cancelledOnlyWhenAskedFor() {
        population();

        PurchaseOrderSummaryResponse summary =
                service.summarizePurchaseOrders(null, List.of(PurchaseOrderStatus.CANCELLED));

        assertThat(summary.getOrderCount()).isEqualTo(90);
        assertThat(summary.getUnitsOpen()).isEqualByComparingTo("3450");
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
                .thenReturn(List.of(new PurchaseOrderStatusRollup(PurchaseOrderStatus.APPROVED, 4L, null, null)));
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

        PurchaseOrderSummaryResponse summary =
                service.summarizePurchaseOrders(null, List.of(PurchaseOrderStatus.CANCELLED));

        assertThat(summary.getOrderCount()).isZero();
        assertThat(summary.getUnitsOpen()).isEqualByComparingTo("0");
        assertThat(summary.getByStatus()).isEmpty();
    }
}
