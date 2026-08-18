package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.inventory.InventoryAvailabilityUpdatedV1;
import com.positivity.domainevents.inventory.ReservationOutcomeV1;
import com.positivity.order.internal.client.InventoryResult;
import com.positivity.order.internal.entity.ExtInventoryAvailability;
import com.positivity.order.internal.entity.ExtProduct;
import com.positivity.order.internal.entity.FulfillmentStatus;
import com.positivity.order.internal.entity.SalesOrderLine;
import com.positivity.order.internal.repository.ExtInventoryAvailabilityRepository;
import com.positivity.order.internal.repository.ExtProductRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import com.positivity.order.internal.repository.SalesOrderLineRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

/**
 * The owned-ATP gate (CAP #1315): the replica that answers "is there enough here", the consumer
 * that feeds it, and the outcome fact that overrides its guess.
 *
 * <p>These are tested together because the gate's correctness is a property of the three
 * interacting, not of any one of them: the adapter's pessimism is only safe because the outcome
 * fact corrects it, and the outcome fact is only trustworthy because the consumer applies it under
 * the stale guard.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Owned-ATP gate — replica, consumer, and reservation outcome (CAP #1315)")
class InventoryAvailabilityGateTest {

    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");
    private static final UUID LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a60");
    private static final UUID AGGREGATE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a61");
    private static final UUID LINE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a62");
    private static final UUID RESERVATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a63");
    private static final UUID BACKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a64");
    private static final String SKU = "SKU-1";

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ExtInventoryAvailabilityRepository extInventoryAvailabilityRepository;

    @Mock
    private ExtProductRepository extProductRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private PurchaseOrderFactPublisher purchaseOrderFactPublisher;

    @Mock
    private SalesOrderLineRepository salesOrderLineRepository;

    private ReplicaInventoryPortAdapter adapter() {
        return new ReplicaInventoryPortAdapter(extInventoryAvailabilityRepository, extProductRepository);
    }

    private InventoryEventsListener listener() {
        return new InventoryEventsListener(
                clock,
                objectMapper,
                processedEventRepository,
                purchaseOrderRepository,
                purchaseOrderFactPublisher,
                extInventoryAvailabilityRepository,
                salesOrderLineRepository);
    }

    private static ExtInventoryAvailability replicaRow(String stockItemId, int atp) {
        return ExtInventoryAvailability.builder()
                .aggregateId(AGGREGATE_ID)
                .stockItemId(stockItemId)
                .locationId(LOCATION_ID)
                .onHandQuantity(atp)
                .allocatedQuantity(0)
                .availableToPromiseQuantity(atp)
                .aggregateVersion(1L)
                .updatedAt(NOW)
                .build();
    }

    private String availabilityEnvelope(String eventId, long aggregateVersion, int atp) {
        InventoryAvailabilityUpdatedV1 payload =
                new InventoryAvailabilityUpdatedV1(PRODUCT_ID.toString(), LOCATION_ID, atp, 0, atp, "EA", 0L, 0L, atp);
        return objectMapper.writeValueAsString(java.util.Map.of(
                "eventId", eventId,
                "eventType", InventoryAvailabilityUpdatedV1.EVENT_TYPE,
                "aggregateId", AGGREGATE_ID.toString(),
                "aggregateVersion", aggregateVersion,
                "payload", payload));
    }

    private String outcomeEnvelope(String eventId, UUID salesOrderLineId, boolean covered) {
        ReservationOutcomeV1 payload = new ReservationOutcomeV1(
                RESERVATION_ID,
                null,
                salesOrderLineId,
                PRODUCT_ID.toString(),
                2,
                covered,
                covered ? null : BACKORDER_ID,
                NOW);
        return objectMapper.writeValueAsString(java.util.Map.of(
                "eventId",
                eventId,
                "eventType",
                ReservationOutcomeV1.EVENT_TYPE,
                "aggregateId",
                RESERVATION_ID.toString(),
                "aggregateVersion",
                1L,
                "payload",
                payload));
    }

    @Nested
    @DisplayName("Replica-backed availability answer")
    class ReplicaAnswer {

        @Test
        @DisplayName("reports sufficient when the selling location's ATP covers the quantity")
        void sufficientWhenCovered() {
            when(extProductRepository.findFirstBySkuIgnoreCaseAndActiveTrue(SKU))
                    .thenReturn(Optional.of(ExtProduct.builder()
                            .sku(SKU)
                            .active(true)
                            .productId(PRODUCT_ID)
                            .build()));
            when(extInventoryAvailabilityRepository.findByStockItemIdAndLocationId(PRODUCT_ID.toString(), LOCATION_ID))
                    .thenReturn(Optional.of(replicaRow(PRODUCT_ID.toString(), 5)));

            InventoryResult result = adapter().checkAvailability(SKU, 2, LOCATION_ID);

            assertThat(result.sufficient()).isTrue();
            assertThat(result.availableQuantity()).isEqualTo(5);
        }

        @Test
        @DisplayName("reports short when ATP is below the quantity")
        void shortWhenBelow() {
            when(extProductRepository.findFirstBySkuIgnoreCaseAndActiveTrue(SKU))
                    .thenReturn(Optional.of(ExtProduct.builder()
                            .sku(SKU)
                            .active(true)
                            .productId(PRODUCT_ID)
                            .build()));
            when(extInventoryAvailabilityRepository.findByStockItemIdAndLocationId(PRODUCT_ID.toString(), LOCATION_ID))
                    .thenReturn(Optional.of(replicaRow(PRODUCT_ID.toString(), 1)));

            assertThat(adapter().checkAvailability(SKU, 2, LOCATION_ID).sufficient())
                    .isFalse();
        }

        @Test
        @DisplayName("treats an absent replica row as short rather than as stocked")
        void absentRowIsShort() {
            when(extProductRepository.findFirstBySkuIgnoreCaseAndActiveTrue(SKU))
                    .thenReturn(Optional.empty());
            when(extInventoryAvailabilityRepository.findByStockItemIdAndLocationId(SKU, LOCATION_ID))
                    .thenReturn(Optional.empty());

            // Absence is not evidence of stock. Answering "sufficient" here would rebuild the
            // always-passing advisory stub this replaced, and would do it invisibly.
            InventoryResult result = adapter().checkAvailability(SKU, 1, LOCATION_ID);

            assertThat(result.sufficient()).isFalse();
            assertThat(result.availableQuantity()).isZero();
        }

        @Test
        @DisplayName("falls back to the raw SKU key when no row exists under the product id")
        void fallsBackToSkuKey() {
            when(extProductRepository.findFirstBySkuIgnoreCaseAndActiveTrue(SKU))
                    .thenReturn(Optional.of(ExtProduct.builder()
                            .sku(SKU)
                            .active(true)
                            .productId(PRODUCT_ID)
                            .build()));
            when(extInventoryAvailabilityRepository.findByStockItemIdAndLocationId(PRODUCT_ID.toString(), LOCATION_ID))
                    .thenReturn(Optional.empty());
            when(extInventoryAvailabilityRepository.findByStockItemIdAndLocationId(SKU, LOCATION_ID))
                    .thenReturn(Optional.of(replicaRow(SKU, 4)));

            // The owner's ledger disagrees with itself about what goes in stockItemId, so a gate
            // that checked only one key would be silently wrong for every item stocked the other way.
            assertThat(adapter().checkAvailability(SKU, 3, LOCATION_ID).sufficient())
                    .isTrue();
        }

        @Test
        @DisplayName("reports short when the order has no selling location")
        void noLocationIsShort() {
            assertThat(adapter().checkAvailability(SKU, 1, null).sufficient()).isFalse();
        }
    }

    @Nested
    @DisplayName("Availability replica feed")
    class AvailabilityFeed {

        @Test
        @DisplayName("applies a fact and records it as processed")
        void appliesFact() {
            when(processedEventRepository.existsById("e1")).thenReturn(false);
            when(extInventoryAvailabilityRepository.findById(AGGREGATE_ID)).thenReturn(Optional.empty());

            listener().onInventoryEvent(availabilityEnvelope("e1", 5L, 7));

            ArgumentCaptor<ExtInventoryAvailability> saved = ArgumentCaptor.forClass(ExtInventoryAvailability.class);
            verify(extInventoryAvailabilityRepository).save(saved.capture());
            assertThat(saved.getValue().getAvailableToPromiseQuantity()).isEqualTo(7);
            assertThat(saved.getValue().getAggregateVersion()).isEqualTo(5L);
            verify(processedEventRepository).save(any());
        }

        @Test
        @DisplayName("is a no-op on replay of an already-processed eventId")
        void replayIsNoOp() {
            when(processedEventRepository.existsById("e1")).thenReturn(true);

            listener().onInventoryEvent(availabilityEnvelope("e1", 5L, 7));

            verify(extInventoryAvailabilityRepository, never()).save(any());
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("discards a fact older than the row it would overwrite")
        void discardsStaleFact() {
            when(processedEventRepository.existsById("e2")).thenReturn(false);
            ExtInventoryAvailability current = replicaRow(PRODUCT_ID.toString(), 9);
            current.setAggregateVersion(10L);
            when(extInventoryAvailabilityRepository.findById(AGGREGATE_ID)).thenReturn(Optional.of(current));

            listener().onInventoryEvent(availabilityEnvelope("e2", 4L, 1));

            // A snapshot is full state: replaying an older one would roll the replica backwards
            // and nothing later would correct it until that pair is touched again.
            verify(extInventoryAvailabilityRepository, never()).save(any());
        }

        @Test
        @DisplayName("rethrows a transient database error so the container retries")
        void rethrowsTransient() {
            when(processedEventRepository.existsById("e3")).thenReturn(false);
            when(extInventoryAvailabilityRepository.findById(AGGREGATE_ID))
                    .thenThrow(new QueryTimeoutException("statement timeout"));

            assertThatThrownBy(() -> listener().onInventoryEvent(availabilityEnvelope("e3", 1L, 1)))
                    .isInstanceOf(QueryTimeoutException.class);
            verify(processedEventRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Reservation outcome resolution")
    class OutcomeResolution {

        private SalesOrderLine line(FulfillmentStatus status) {
            SalesOrderLine line = new SalesOrderLine();
            line.setOrderLineId(LINE_ID);
            line.setItemSku(SKU);
            line.setQuantity(2);
            line.setFulfillmentStatus(status);
            return line;
        }

        @Test
        @DisplayName("corrects an optimistic AVAILABLE label to BACKORDER and records the backorder")
        void uncoveredCorrectsToBackorder() {
            when(processedEventRepository.existsById("o1")).thenReturn(false);
            SalesOrderLine line = line(FulfillmentStatus.AVAILABLE);
            when(salesOrderLineRepository.findById(LINE_ID)).thenReturn(Optional.of(line));

            listener().onInventoryEvent(outcomeEnvelope("o1", LINE_ID, false));

            assertThat(line.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.BACKORDER);
            assertThat(line.getBackorderId()).isEqualTo(BACKORDER_ID);
            assertThat(line.getReservationId()).isEqualTo(RESERVATION_ID);
            verify(salesOrderLineRepository).save(line);
        }

        @Test
        @DisplayName("corrects a pessimistic BACKORDER label back to AVAILABLE with no backorder")
        void coveredCorrectsToAvailable() {
            when(processedEventRepository.existsById("o2")).thenReturn(false);
            SalesOrderLine line = line(FulfillmentStatus.BACKORDER);
            when(salesOrderLineRepository.findById(LINE_ID)).thenReturn(Optional.of(line));

            listener().onInventoryEvent(outcomeEnvelope("o2", LINE_ID, true));

            // An absent replica row makes checkout label a stocked line BACKORDER; the owner's own
            // answer is what puts it right.
            assertThat(line.getFulfillmentStatus()).isEqualTo(FulfillmentStatus.AVAILABLE);
            assertThat(line.getBackorderId()).isNull();
            verify(salesOrderLineRepository).save(line);
        }

        @Test
        @DisplayName("ignores an outcome for a line this module does not hold")
        void ignoresUnknownLine() {
            when(processedEventRepository.existsById("o3")).thenReturn(false);
            when(salesOrderLineRepository.findById(LINE_ID)).thenReturn(Optional.empty());

            listener().onInventoryEvent(outcomeEnvelope("o3", LINE_ID, true));

            verify(salesOrderLineRepository, never()).save(any());
            // Still recorded: the owner's reconciliation manifest counts every fact offered here.
            verify(processedEventRepository).save(any());
        }
    }
}
