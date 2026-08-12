package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.catalog.ProductUpdatedV1;
import com.positivity.domainevents.customer.CustomerPartyUpdatedV1;
import com.positivity.domainevents.location.LocationUpdatedV1;
import com.positivity.domainevents.vehicle.VehicleUpdatedV1;
import com.positivity.domainevents.workorder.WorkorderUpdatedV1;
import com.positivity.order.internal.entity.ProcessedEvent;
import com.positivity.order.internal.repository.ExtBillingRulesRepository;
import com.positivity.order.internal.repository.ExtCustomerRepository;
import com.positivity.order.internal.repository.ExtEstimateLineRepository;
import com.positivity.order.internal.repository.ExtEstimateRepository;
import com.positivity.order.internal.repository.ExtLocationRepository;
import com.positivity.order.internal.repository.ExtProductRepository;
import com.positivity.order.internal.repository.ExtVehicleRepository;
import com.positivity.order.internal.repository.ExtWorkorderLineRepository;
import com.positivity.order.internal.repository.ExtWorkorderRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

/**
 * The five {@code ext_*} replica listeners are documented as sharing one consumer
 * contract, and each of their class comments points at the others for it. This
 * test holds that shared contract in one place so a listener cannot quietly drop
 * half of it:
 *
 * <ul>
 * <li><b>A message that is not JSON, or not a type this module replicates, is
 * skipped without a dedup row.</b> Recording it would make
 * {@code processed_events} a log of what arrived rather than what was applied,
 * and would hide a later genuine delivery of the same id.</li>
 * <li><b>An envelope with no {@code eventId} is skipped</b>, because there is
 * nothing to de-duplicate it by and at-least-once delivery would let it apply
 * twice.</li>
 * <li><b>A replayed {@code eventId} is a no-op.</b></li>
 * <li><b>A transient database error is rethrown</b> so the Kafka container
 * retries. Swallowing it would silently drop the fact and leave the replica
 * permanently behind.</li>
 * <li><b>A malformed payload is swallowed and left unrecorded.</b> A poison
 * message must not wedge the partition — but it also must not be marked
 * processed, so a corrected redelivery still applies.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ext_* replica listeners — shared consumer contract")
class ReplicaListenerContractTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private ExtCustomerRepository extCustomerRepository;

    @Mock
    private ExtBillingRulesRepository extBillingRulesRepository;

    @Mock
    private ExtVehicleRepository extVehicleRepository;

    @Mock
    private ExtProductRepository extProductRepository;

    @Mock
    private ExtLocationRepository extLocationRepository;

    @Mock
    private ExtWorkorderRepository extWorkorderRepository;

    @Mock
    private ExtWorkorderLineRepository extWorkorderLineRepository;

    @Mock
    private ExtEstimateRepository extEstimateRepository;

    @Mock
    private ExtEstimateLineRepository extEstimateLineRepository;

    /**
     * One listener under test: how to hand it a message, the event type and id field it
     * replicates, the owner it stamps on the dedup row, and how to make its replica repository
     * fail transiently.
     */
    private record Listener(
            String name, String eventType, String idField, String owner, Consumer<String> dispatch, Runnable failHard) {
        @Override
        public String toString() {
            return name;
        }
    }

    static final List<String> LISTENERS = List.of("customer", "vehicle", "catalog", "location", "workorder");

    private java.util.Map<String, Listener> listeners;

    @BeforeEach
    void setUp() {
        when(extCustomerRepository.findById(any())).thenReturn(Optional.empty());
        when(extVehicleRepository.findById(any())).thenReturn(Optional.empty());
        when(extProductRepository.findById(any())).thenReturn(Optional.empty());
        when(extLocationRepository.findById(any())).thenReturn(Optional.empty());
        when(extWorkorderRepository.findById(any())).thenReturn(Optional.empty());
        when(processedEventRepository.existsById(any())).thenReturn(false);

        CustomerEventsListener customer = new CustomerEventsListener(
                clock, objectMapper, processedEventRepository, extCustomerRepository, extBillingRulesRepository);
        VehicleEventsListener vehicle =
                new VehicleEventsListener(clock, objectMapper, processedEventRepository, extVehicleRepository);
        ProductEventsListener product =
                new ProductEventsListener(clock, objectMapper, processedEventRepository, extProductRepository);
        LocationEventsListener location =
                new LocationEventsListener(clock, objectMapper, processedEventRepository, extLocationRepository);
        WorkorderEventsListener workorder = new WorkorderEventsListener(
                clock,
                objectMapper,
                processedEventRepository,
                extWorkorderRepository,
                extWorkorderLineRepository,
                extEstimateRepository,
                extEstimateLineRepository);

        listeners = java.util.Map.of(
                "customer",
                        new Listener(
                                "customer",
                                CustomerPartyUpdatedV1.EVENT_TYPE,
                                "partyId",
                                CustomerEventsListener.OWNER,
                                customer::onCustomerEvent,
                                () -> when(extCustomerRepository.findById(any()))
                                        .thenThrow(new QueryTimeoutException("lock wait"))),
                "vehicle",
                        new Listener(
                                "vehicle",
                                VehicleUpdatedV1.EVENT_TYPE,
                                "vehicleId",
                                VehicleEventsListener.OWNER,
                                vehicle::onVehicleEvent,
                                () -> when(extVehicleRepository.findById(any()))
                                        .thenThrow(new QueryTimeoutException("lock wait"))),
                "catalog",
                        new Listener(
                                "catalog",
                                ProductUpdatedV1.EVENT_TYPE,
                                "productId",
                                ProductEventsListener.OWNER,
                                product::onCatalogEvent,
                                () -> when(extProductRepository.findById(any()))
                                        .thenThrow(new QueryTimeoutException("lock wait"))),
                "location",
                        new Listener(
                                "location",
                                LocationUpdatedV1.EVENT_TYPE,
                                "locationId",
                                LocationEventsListener.OWNER,
                                location::onLocationEvent,
                                () -> when(extLocationRepository.findById(any()))
                                        .thenThrow(new QueryTimeoutException("lock wait"))),
                "workorder",
                        new Listener(
                                "workorder",
                                WorkorderUpdatedV1.EVENT_TYPE,
                                "workorderId",
                                WorkorderEventsListener.OWNER,
                                workorder::onWorkorderEvent,
                                () -> when(extWorkorderRepository.findById(any()))
                                        .thenThrow(new QueryTimeoutException("lock wait"))));
    }

    private Listener listener(String name) {
        return listeners.get(name);
    }

    private String envelope(Listener listener, String eventId, String idValue) {
        return """
                {"eventId":"%s","eventType":"%s","aggregateVersion":3,"payload":{"%s":"%s","accountId":"%s"}}""".formatted(eventId, listener.eventType(), listener.idField(), idValue, ID);
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("applies a well-formed fact and stamps its own owner on the dedup row")
    void happyPathRecordsProcessedEvent(String name) {
        Listener listener = listener(name);

        listener.dispatch().accept(envelope(listener, "evt-1", ID.toString()));

        ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(captor.capture());
        assertThat(captor.getValue().getEventId()).isEqualTo("evt-1");
        assertThat(captor.getValue().getOwner()).isEqualTo(listener.owner());
        assertThat(captor.getValue().getProcessedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("skips a message that is not JSON without recording it")
    void unparsableMessageIsSkipped(String name) {
        listener(name).dispatch().accept("{not json");

        verify(processedEventRepository, never()).save(any());
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("skips an event type this module does not replicate without recording it")
    void unrelatedEventTypeIsSkipped(String name) {
        listener(name).dispatch().accept("""
                        {"eventId":"evt-1","eventType":"some.other.thing","payload":{}}""");

        verify(processedEventRepository, never()).save(any());
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("skips an envelope with no eventId, since nothing could de-duplicate it")
    void missingEventIdIsSkipped(String name) {
        Listener listener = listener(name);

        listener.dispatch().accept(envelope(listener, "", ID.toString()));

        verify(processedEventRepository, never()).save(any());
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("no-ops on a replayed eventId")
    void replayIsIgnored(String name) {
        when(processedEventRepository.existsById("evt-1")).thenReturn(true);
        Listener listener = listener(name);

        listener.dispatch().accept(envelope(listener, "evt-1", ID.toString()));

        verify(processedEventRepository, never()).save(any());
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("rethrows a transient database error so the container retries instead of losing the fact")
    void transientDatabaseErrorIsRethrown(String name) {
        Listener listener = listener(name);
        listener.failHard().run();

        assertThatThrownBy(() -> listener.dispatch().accept(envelope(listener, "evt-1", ID.toString())))
                .isInstanceOf(QueryTimeoutException.class);

        verify(processedEventRepository, never()).save(any());
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("swallows a malformed payload but leaves it unrecorded so a corrected redelivery still applies")
    void malformedPayloadIsSkippedButNotRecorded(String name) {
        Listener listener = listener(name);

        listener.dispatch().accept(envelope(listener, "evt-1", "not-a-uuid"));

        verify(processedEventRepository, never()).save(any());
    }
}
