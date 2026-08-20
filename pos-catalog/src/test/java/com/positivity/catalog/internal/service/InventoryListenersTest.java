package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.entity.ExtInventoryAvailabilityReplica;
import com.positivity.catalog.internal.entity.ExtProductLeadTimeReplica;
import com.positivity.catalog.internal.entity.ProcessedEvent;
import com.positivity.catalog.internal.repository.ExtInventoryAvailabilityReplicaRepository;
import com.positivity.catalog.internal.repository.ExtProductLeadTimeReplicaRepository;
import com.positivity.catalog.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.domainevents.UuidV7Timestamps;
import com.positivity.domainevents.inventory.InventoryAvailabilityUpdatedV1;
import com.positivity.domainevents.inventory.LeadTimeUpdatedV1;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for pos-catalog's inventory replica and reconciliation listeners
 * (ADR-0044 §4/§6).
 *
 * <p>
 * These replicas are what product detail pages read for "in stock" and
 * "ships in N days" without a synchronous call to pos-inventory. The
 * availability fact is keyed on the envelope's aggregateId (a per-sku-location
 * aggregate), not on a payload field — pinned here because a refactor that
 * switched to a payload field would silently fork the replica's identity from
 * the producer's.
 *
 * <p>
 * The delivery and reconciliation contracts are the same ones pinned in the
 * sibling modules: dedup by eventId, malformed payloads swallowed but recorded,
 * transient errors rethrown, strictly-lower-version stale guard, stateless
 * drift verdict with the replay routed to the owner's commands topic.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("pos-catalog inventory listeners — availability and lead-time replicas")
class InventoryListenersTest {

    private static final UUID AGGREGATE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");
    private static final Instant WINDOW_START = Instant.parse("2026-08-11T09:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-08-11T09:05:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private ExtInventoryAvailabilityReplicaRepository availabilityRepository;

    @Mock
    private ExtProductLeadTimeReplicaRepository leadTimeRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private ObjectProvider<MeterRegistry> meterRegistryProvider;

    private SimpleMeterRegistry meterRegistry;
    private InventoryEventsListener listener;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(availabilityRepository.findById(any())).thenReturn(Optional.empty());
        when(leadTimeRepository.findById(any())).thenReturn(Optional.empty());
        listener = new InventoryEventsListener(
                clock,
                objectMapper,
                processedEventRepository,
                availabilityRepository,
                leadTimeRepository,
                org.mockito.Mockito.mock(ObjectProvider.class));
    }

    private static String availability(String eventId, long version, int atp) {
        return """
                {"eventId":"%s","eventType":"%s","aggregateVersion":%d,"aggregateId":"%s",
                 "payload":{"stockItemId":"BRK-100","locationId":"%s","onHandQuantity":10,
                   "allocatedQuantity":2,"availableToPromiseQuantity":%d,"unitOfMeasure":"EA",
                   "incomingQuantity":5,"outgoingQuantity":1,"projectedAvailableQuantity":12}}
                """.formatted(
                        eventId, InventoryAvailabilityUpdatedV1.EVENT_TYPE, version, AGGREGATE_ID, LOCATION_ID, atp);
    }

    private static String leadTime(String eventId, long version) {
        return """
                {"eventId":"%s","eventType":"%s","aggregateVersion":%d,
                 "payload":{"productId":"%s","minDays":2,"maxDays":5,"displayText":"2-5 days",
                   "source":"SUPPLIER","confidence":"HIGH","asOf":"2026-08-10T00:00:00Z"}}
                """.formatted(eventId, LeadTimeUpdatedV1.EVENT_TYPE, version, PRODUCT_ID);
    }

    @Nested
    @DisplayName("availability replica")
    class Availability {

        @Test
        @DisplayName("keys the replica on the envelope's aggregateId, not a payload field")
        void keyedOnAggregateId() {
            listener.onInventoryEvent(availability("evt-1", 3, 8));

            ArgumentCaptor<ExtInventoryAvailabilityReplica> captor =
                    ArgumentCaptor.forClass(ExtInventoryAvailabilityReplica.class);
            verify(availabilityRepository).save(captor.capture());
            ExtInventoryAvailabilityReplica saved = captor.getValue();
            // The aggregateId is the producer's identity for this sku-location; deriving a key
            // from payload fields instead would fork the replica's identity from the producer's.
            assertThat(saved.getAggregateId()).isEqualTo(AGGREGATE_ID);
            assertThat(saved.getStockItemId()).isEqualTo("BRK-100");
            assertThat(saved.getLocationId()).isEqualTo(LOCATION_ID);
            assertThat(saved.getOnHandQuantity()).isEqualByComparingTo("10");
            assertThat(saved.getAvailableToPromiseQuantity()).isEqualByComparingTo("8");
            assertThat(saved.getUnitOfMeasure()).isEqualTo("EA");
            assertThat(saved.getAggregateVersion()).isEqualTo(3);
            assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("lands a divisible product's fractional quantities from the real contract type")
        void carriesDecimalQuantitiesFromTheContract() {
            // ADR-0055 stage 2 (#1414) AC: pos-catalog consumes InventoryAvailabilityUpdatedV1 and
            // is the module easiest to forget when that contract changes — it sits outside the
            // #1315 gate work and its other test here hand-writes the payload JSON, which would
            // keep passing through a type change on the record. This one serialises the actual
            // contract type, so a narrowing of these fields breaks here rather than silently
            // truncating a bulk product's on-hand on a product-detail page.
            InventoryAvailabilityUpdatedV1 payload = new InventoryAvailabilityUpdatedV1(
                    "BULK-OIL-DRUM",
                    LOCATION_ID,
                    new BigDecimal("120.5000"),
                    new BigDecimal("0.2500"),
                    new BigDecimal("120.2500"),
                    "LB",
                    new BigDecimal("2.5000"),
                    new BigDecimal("1.2500"),
                    new BigDecimal("121.7500"));

            listener.onInventoryEvent(objectMapper.writeValueAsString(java.util.Map.of(
                    "eventId",
                    "evt-decimal",
                    "eventType",
                    InventoryAvailabilityUpdatedV1.EVENT_TYPE,
                    "aggregateVersion",
                    7L,
                    "aggregateId",
                    AGGREGATE_ID.toString(),
                    "payload",
                    payload)));

            ArgumentCaptor<ExtInventoryAvailabilityReplica> captor =
                    ArgumentCaptor.forClass(ExtInventoryAvailabilityReplica.class);
            verify(availabilityRepository).save(captor.capture());
            ExtInventoryAvailabilityReplica saved = captor.getValue();
            assertThat(saved.getStockItemId()).isEqualTo("BULK-OIL-DRUM");
            // compareTo, not equals: scale is how the number was spelled, not what it means.
            assertThat(saved.getOnHandQuantity()).isEqualByComparingTo("120.5");
            assertThat(saved.getAllocatedQuantity()).isEqualByComparingTo("0.25");
            assertThat(saved.getAvailableToPromiseQuantity()).isEqualByComparingTo("120.25");
            assertThat(saved.getUnitOfMeasure()).isEqualTo("LB");
        }

        @Test
        @DisplayName("skips a strictly-older snapshot but re-applies an equal one")
        void staleGuard() {
            when(availabilityRepository.findById(AGGREGATE_ID))
                    .thenReturn(Optional.of(ExtInventoryAvailabilityReplica.builder()
                            .aggregateId(AGGREGATE_ID)
                            .aggregateVersion(3)
                            .build()));

            listener.onInventoryEvent(availability("evt-1", 2, 8));
            verify(availabilityRepository, never()).save(any());

            listener.onInventoryEvent(availability("evt-2", 3, 8));
            verify(availabilityRepository).save(any());
        }
    }

    @Nested
    @DisplayName("lead-time replica")
    class LeadTime {

        @Test
        @DisplayName("replicates the lead-time fields the product page displays")
        void mapsLeadTime() {
            listener.onInventoryEvent(leadTime("evt-1", 2));

            ArgumentCaptor<ExtProductLeadTimeReplica> captor = ArgumentCaptor.forClass(ExtProductLeadTimeReplica.class);
            verify(leadTimeRepository).save(captor.capture());
            ExtProductLeadTimeReplica saved = captor.getValue();
            assertThat(saved.getProductId()).isEqualTo(PRODUCT_ID);
            assertThat(saved.getMinDays()).isEqualTo(2);
            assertThat(saved.getMaxDays()).isEqualTo(5);
            assertThat(saved.getDisplayText()).isEqualTo("2-5 days");
        }
    }

    @Nested
    @DisplayName("delivery contract")
    class DeliveryContract {

        @Test
        @DisplayName("records a dedup row for an ignored type, keeping the manifest count aligned")
        void ignoredTypeIsRecorded() {
            listener.onInventoryEvent("""
                    {"eventId":"evt-9","eventType":"inventory.something.else","payload":{}}""");

            ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
            verify(processedEventRepository).save(captor.capture());
            assertThat(captor.getValue().getOwner()).isEqualTo("inventory");
        }

        @Test
        @DisplayName("skips unparseable messages, missing eventIds, and replays")
        void deliveryGuards() {
            listener.onInventoryEvent("{not json");
            listener.onInventoryEvent(availability("", 1, 8));
            when(processedEventRepository.existsById("evt-1")).thenReturn(true);
            listener.onInventoryEvent(availability("evt-1", 1, 8));

            verify(processedEventRepository, never()).save(any());
            verify(availabilityRepository, never()).save(any());
        }

        @Test
        @DisplayName("rethrows a transient database error but swallows and records a malformed payload")
        void transientVersusMalformed() {
            doThrow(new QueryTimeoutException("lock wait"))
                    .when(availabilityRepository)
                    .findById(any());

            assertThatThrownBy(() -> listener.onInventoryEvent(availability("evt-1", 1, 8)))
                    .isInstanceOf(QueryTimeoutException.class);
            verify(processedEventRepository, never()).save(any());

            listener.onInventoryEvent("""
                    {"eventId":"evt-2","eventType":"%s","payload":{"productId":"not-a-uuid"}}""".formatted(LeadTimeUpdatedV1.EVENT_TYPE));
            verify(processedEventRepository).save(any());
        }
    }

    @Nested
    @DisplayName("reconciliation manifest listener")
    class Manifest {

        private InventoryManifestListener manifestListener() {
            InventoryManifestListener manifestListener = new InventoryManifestListener(
                    processedEventRepository, kafkaTemplate, objectMapper, meterRegistryProvider);
            // Field name is a copy-paste leftover (same as pos-invoice's workorder listener); the
            // @Value key binds the inventory topic, so routing is correct in production.
            ReflectionTestUtils.setField(manifestListener, "locationCommandsTopic", "inventory.commands.v1");
            return manifestListener;
        }

        private String manifestMessage(long eventCount, String checksum) {
            return """
                    {"eventId":"evt-1","eventType":"x.reconciliation.manifest",
                     "payload":{"windowStartUtc":"%s","windowEndUtc":"%s","eventCount":%d,
                       "eventIdsChecksum":"%s","eventTypeCounts":null}}
                    """.formatted(WINDOW_START, WINDOW_END, eventCount, checksum);
        }

        private void replicaHolds(List<String> eventIds) {
            when(processedEventRepository.findEventIdsInRange(
                            InventoryEventsListener.OWNER,
                            UuidV7Timestamps.minStringAt(WINDOW_START),
                            UuidV7Timestamps.minStringAt(WINDOW_END)))
                    .thenReturn(eventIds);
        }

        private double driftCount() {
            return meterRegistry.find("replica.drift").tag("owner", "inventory").counters().stream()
                    .mapToDouble(io.micrometer.core.instrument.Counter::count)
                    .sum();
        }

        @Test
        @DisplayName("stays silent on a matching window")
        void matchingWindow() {
            List<String> ids = List.of("019ff000-0000-7000-8000-000000000001");
            replicaHolds(ids);

            manifestListener().onManifest(manifestMessage(1, ReconciliationManifestV1.checksumOf(ids)));

            verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
            assertThat(driftCount()).isZero();
        }

        @Test
        @DisplayName("counts drift and requests a replay for the failed window on inventory's topic")
        void driftRequestsReplay() {
            replicaHolds(List.of());

            manifestListener().onManifest(manifestMessage(2, "owner-checksum"));

            assertThat(driftCount()).isEqualTo(1.0);
            ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
            verify(kafkaTemplate)
                    .send(
                            org.mockito.ArgumentMatchers.eq("inventory.commands.v1"),
                            org.mockito.ArgumentMatchers.eq(WINDOW_START.toString()),
                            command.capture());
            assertThat(objectMapper
                            .readTree(command.getValue())
                            .path("payload")
                            .path("since")
                            .asString())
                    .isEqualTo(WINDOW_START.toString());
        }

        @Test
        @DisplayName("drops an unparseable manifest and survives a failed replay publish")
        void robustness() {
            manifestListener().onManifest("{not json");
            verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());

            replicaHolds(List.of());
            when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                    .thenThrow(new IllegalStateException("broker down"));

            manifestListener().onManifest(manifestMessage(3, "owner-checksum"));

            assertThat(driftCount()).isEqualTo(1.0);
        }
    }
}
