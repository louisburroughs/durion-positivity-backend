package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.inventory.ReservationOutcomeV1;
import com.positivity.inventory.internal.dto.backorder.BackorderResponse;
import com.positivity.inventory.internal.dto.reservation.ReservationResponse;
import com.positivity.inventory.internal.entity.InventoryStockSummary;
import com.positivity.inventory.internal.entity.ReservationEntity;
import com.positivity.inventory.internal.enums.BackorderStatus;
import com.positivity.inventory.internal.enums.ReservationStatus;
import com.positivity.inventory.internal.repository.InventoryStockSummaryRepository;
import com.positivity.inventory.internal.repository.ReservationRepository;
import com.positivity.inventory.service.BackorderService;
import com.positivity.inventory.service.ReservationService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReservationRequestHandlerTest {

    private static final UUID RESERVATION_ID = UUID.fromString("01960004-0004-7000-8000-000000000001");
    private static final UUID WORKORDER_LINE_ID = UUID.fromString("01960004-0004-7000-8000-000000000002");
    private static final UUID SALES_ORDER_LINE_ID = UUID.fromString("01960004-0004-7000-8000-000000000003");
    private static final UUID STOCK_ITEM_ID = UUID.fromString("01960004-0004-7000-8000-000000000004");
    private static final UUID LOCATION_ID = UUID.fromString("01960004-0004-7000-8000-000000000005");
    private static final UUID BACKORDER_ID = UUID.fromString("01960004-0004-7000-8000-000000000006");

    @Mock
    private ReservationService reservationService;

    @Mock
    private BackorderService backorderService;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private InventoryStockSummaryRepository summaryRepository;

    @Mock
    private InventoryFactPublisher inventoryFactPublisher;

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC);

    private ReservationRequestHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ReservationRequestHandler(
                reservationService,
                backorderService,
                reservationRepository,
                summaryRepository,
                inventoryFactPublisher,
                fixedClock);
    }

    private static InventoryStockSummary summaryWithAtp(long atp) {
        InventoryStockSummary summary = mock(InventoryStockSummary.class);
        when(summary.getAtp()).thenReturn(atp);
        return summary;
    }

    private ReservationResponse reservationResponse() {
        return ReservationResponse.builder()
                .reservationId(RESERVATION_ID)
                .status(ReservationStatus.PENDING.name())
                .build();
    }

    @Test
    @DisplayName("rejects when neither demand line is set")
    void handle_neitherDemandLine_throws() {
        assertThatThrownBy(() -> handler.handle(null, null, STOCK_ITEM_ID, 5, LOCATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    @DisplayName("rejects when both demand lines are set")
    void handle_bothDemandLines_throws() {
        assertThatThrownBy(() -> handler.handle(WORKORDER_LINE_ID, SALES_ORDER_LINE_ID, STOCK_ITEM_ID, 5, LOCATION_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    @DisplayName("covered demand publishes a covered outcome fact and never opens a backorder")
    void handle_workorderLine_covered_publishesCoveredFact() {
        when(reservationService.createOrUpdateReservation(any())).thenReturn(reservationResponse());
        InventoryStockSummary summary = summaryWithAtp(10);
        when(summaryRepository.findByStockItemIdAndLocationId(STOCK_ITEM_ID.toString(), LOCATION_ID))
                .thenReturn(Optional.of(summary));

        handler.handle(WORKORDER_LINE_ID, null, STOCK_ITEM_ID, 5, LOCATION_ID);

        verify(backorderService, never()).createBackorder(any(), any(), anyInt(), any());
        ArgumentCaptor<ReservationOutcomeV1> factCaptor = ArgumentCaptor.forClass(ReservationOutcomeV1.class);
        verify(inventoryFactPublisher).recordReservationOutcome(factCaptor.capture());
        ReservationOutcomeV1 fact = factCaptor.getValue();
        assertThat(fact.covered()).isTrue();
        assertThat(fact.backorderId()).isNull();
        assertThat(fact.workorderLineId()).isEqualTo(WORKORDER_LINE_ID);
        assertThat(fact.salesOrderLineId()).isNull();
    }

    @Test
    @DisplayName("uncovered sales-order demand backorders the reservation and publishes an uncovered outcome fact")
    void handle_salesOrderLine_uncovered_backordersAndPublishesFact() {
        when(reservationService.createOrUpdateReservation(any())).thenReturn(reservationResponse());
        InventoryStockSummary summary = summaryWithAtp(2);
        when(summaryRepository.findByStockItemIdAndLocationId(STOCK_ITEM_ID.toString(), LOCATION_ID))
                .thenReturn(Optional.of(summary));
        ReservationEntity reservation = ReservationEntity.builder()
                .reservationId(RESERVATION_ID)
                .salesOrderLineId(SALES_ORDER_LINE_ID)
                .stockItemId(STOCK_ITEM_ID)
                .requiredQuantity(5)
                .status(ReservationStatus.PENDING)
                .build();
        when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Optional.of(reservation));
        when(backorderService.createBackorderForSalesOrderLine(
                        SALES_ORDER_LINE_ID, STOCK_ITEM_ID.toString(), 3, LOCATION_ID))
                .thenReturn(BackorderResponse.builder()
                        .backorderId(BACKORDER_ID)
                        .salesOrderLineId(SALES_ORDER_LINE_ID)
                        .status(BackorderStatus.OPEN)
                        .build());

        handler.handle(null, SALES_ORDER_LINE_ID, STOCK_ITEM_ID, 5, LOCATION_ID);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.BACKORDERED);
        verify(reservationRepository).save(reservation);
        verify(backorderService, never()).createBackorder(any(), any(), anyInt(), any());

        ArgumentCaptor<ReservationOutcomeV1> factCaptor = ArgumentCaptor.forClass(ReservationOutcomeV1.class);
        verify(inventoryFactPublisher).recordReservationOutcome(factCaptor.capture());
        ReservationOutcomeV1 fact = factCaptor.getValue();
        assertThat(fact.covered()).isFalse();
        assertThat(fact.backorderId()).isEqualTo(BACKORDER_ID);
        assertThat(fact.salesOrderLineId()).isEqualTo(SALES_ORDER_LINE_ID);
        assertThat(fact.workorderLineId()).isNull();
    }
}
