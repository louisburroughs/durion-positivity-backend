package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
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
import com.positivity.inventory.internal.reservation.service.BackorderService;
import com.positivity.inventory.internal.reservation.service.ReservationRequestHandler;
import com.positivity.inventory.internal.reservation.service.ReservationService;
import java.math.BigDecimal;
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

    @Mock
    private UomConversionService uomConversionService;

    @Mock
    private QuantityScaleGuard quantityScaleGuard;

    private final Clock fixedClock = Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneOffset.UTC);

    private ReservationRequestHandler handler;

    @BeforeEach
    void setUp() {
        // Every call that reaches the conversion step runs the quantity through the guard
        // (ADR-0055 stage 3, #1415); a passthrough default keeps the pre-existing tests exercising
        // base-unit demand unchanged. lenient() because the two "rejects when ..." tests throw
        // before reaching it.
        lenient()
                .when(quantityScaleGuard.requirePostable(any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(3));
        handler = new ReservationRequestHandler(
                reservationService,
                backorderService,
                reservationRepository,
                summaryRepository,
                inventoryFactPublisher,
                uomConversionService,
                quantityScaleGuard,
                fixedClock);
    }

    private static InventoryStockSummary summaryWithAtp(long atp) {
        InventoryStockSummary summary = mock(InventoryStockSummary.class);
        when(summary.getAtp()).thenReturn(BigDecimal.valueOf(atp));
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
        assertThatThrownBy(() -> handler.handle(null, null, STOCK_ITEM_ID, new BigDecimal("5"), LOCATION_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    @DisplayName("rejects when both demand lines are set")
    void handle_bothDemandLines_throws() {
        assertThatThrownBy(() -> handler.handle(
                        WORKORDER_LINE_ID, SALES_ORDER_LINE_ID, STOCK_ITEM_ID, new BigDecimal("5"), LOCATION_ID, null))
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

        handler.handle(WORKORDER_LINE_ID, null, STOCK_ITEM_ID, new BigDecimal("5"), LOCATION_ID, null);

        verify(backorderService, never()).createBackorder(any(), any(), any(), any());
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
                .requiredQuantity(new BigDecimal("5"))
                .status(ReservationStatus.PENDING)
                .build();
        when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Optional.of(reservation));
        when(backorderService.createBackorderForSalesOrderLine(
                        SALES_ORDER_LINE_ID, STOCK_ITEM_ID.toString(), new BigDecimal("3"), LOCATION_ID))
                .thenReturn(BackorderResponse.builder()
                        .backorderId(BACKORDER_ID)
                        .salesOrderLineId(SALES_ORDER_LINE_ID)
                        .status(BackorderStatus.OPEN)
                        .build());

        handler.handle(null, SALES_ORDER_LINE_ID, STOCK_ITEM_ID, new BigDecimal("5"), LOCATION_ID, null);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.BACKORDERED);
        verify(reservationRepository).save(reservation);
        verify(backorderService, never()).createBackorder(any(), any(), any(), any());

        ArgumentCaptor<ReservationOutcomeV1> factCaptor = ArgumentCaptor.forClass(ReservationOutcomeV1.class);
        verify(inventoryFactPublisher).recordReservationOutcome(factCaptor.capture());
        ReservationOutcomeV1 fact = factCaptor.getValue();
        assertThat(fact.covered()).isFalse();
        assertThat(fact.backorderId()).isEqualTo(BACKORDER_ID);
        assertThat(fact.salesOrderLineId()).isEqualTo(SALES_ORDER_LINE_ID);
        assertThat(fact.workorderLineId()).isNull();
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("Document-to-base conversion at the reservation boundary (ADR-0055 stage 3, #1415)")
    class UomConversion {

        @Test
        @DisplayName("a uomCode converts via the DOWN-rounding reservation variant before anything else runs")
        void convertsWithDownRoundingBeforeReserving() {
            when(uomConversionService.toBaseQuantityForReservation(STOCK_ITEM_ID, "QT", new BigDecimal("4.5")))
                    .thenReturn(new BigDecimal("4"));
            when(quantityScaleGuard.requirePostable(
                            STOCK_ITEM_ID, STOCK_ITEM_ID.toString(), "requiredQuantity", new BigDecimal("4")))
                    .thenReturn(new BigDecimal("4"));
            when(reservationService.createOrUpdateReservation(any())).thenReturn(reservationResponse());
            InventoryStockSummary summary = summaryWithAtp(10);
            when(summaryRepository.findByStockItemIdAndLocationId(STOCK_ITEM_ID.toString(), LOCATION_ID))
                    .thenReturn(Optional.of(summary));

            handler.handle(WORKORDER_LINE_ID, null, STOCK_ITEM_ID, new BigDecimal("4.5"), LOCATION_ID, "QT");

            org.mockito.ArgumentCaptor<com.positivity.inventory.internal.dto.reservation.CreateReservationRequest>
                    requestCaptor = org.mockito.ArgumentCaptor.forClass(
                            com.positivity.inventory.internal.dto.reservation.CreateReservationRequest.class);
            verify(reservationService).createOrUpdateReservation(requestCaptor.capture());
            assertThat(requestCaptor.getValue().getRequiredQuantity()).isEqualByComparingTo("4");

            ArgumentCaptor<ReservationOutcomeV1> factCaptor = ArgumentCaptor.forClass(ReservationOutcomeV1.class);
            verify(inventoryFactPublisher).recordReservationOutcome(factCaptor.capture());
            assertThat(factCaptor.getValue().requiredQuantity()).isEqualByComparingTo("4");
        }

        @Test
        @DisplayName("a null uomCode skips conversion entirely, matching pre-#1415 behavior")
        void nullUomCodeSkipsConversion() {
            when(quantityScaleGuard.requirePostable(
                            STOCK_ITEM_ID, STOCK_ITEM_ID.toString(), "requiredQuantity", new BigDecimal("5")))
                    .thenReturn(new BigDecimal("5"));
            when(reservationService.createOrUpdateReservation(any())).thenReturn(reservationResponse());
            InventoryStockSummary summary = summaryWithAtp(10);
            when(summaryRepository.findByStockItemIdAndLocationId(STOCK_ITEM_ID.toString(), LOCATION_ID))
                    .thenReturn(Optional.of(summary));

            handler.handle(WORKORDER_LINE_ID, null, STOCK_ITEM_ID, new BigDecimal("5"), LOCATION_ID, null);

            verify(uomConversionService, never()).toBaseQuantityForReservation(any(), any(), any());
            verify(uomConversionService, never()).toBaseQuantity(any(), any(), any());
        }

        @Test
        @DisplayName(
                "a uomCode with no conversion path surfaces UomConversionUndefinedException and reserves " + "nothing")
        void undefinedConversionSurfacesAndReservesNothing() {
            when(uomConversionService.toBaseQuantityForReservation(STOCK_ITEM_ID, "QT", new BigDecimal("4.5")))
                    .thenThrow(com.positivity.inventory.internal.exception.UomConversionUndefinedException.unknownUom(
                            STOCK_ITEM_ID, "QT"));

            assertThatThrownBy(() -> handler.handle(
                            WORKORDER_LINE_ID, null, STOCK_ITEM_ID, new BigDecimal("4.5"), LOCATION_ID, "QT"))
                    .isInstanceOf(com.positivity.inventory.internal.exception.UomConversionUndefinedException.class);

            verify(reservationService, never()).createOrUpdateReservation(any());
            verify(inventoryFactPublisher, never()).recordReservationOutcome(any());
        }
    }
}
