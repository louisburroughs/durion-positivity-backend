package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.domainevents.UuidV7Timestamps;
import com.positivity.domainevents.customer.CustomerPartyDeletedV1;
import com.positivity.domainevents.customer.CustomerPartyUpdatedV1;
import com.positivity.domainevents.people.StaffingAssignmentUpdatedV1;
import com.positivity.domainevents.peoplecontact.PersonUpdatedV1;
import com.positivity.domainevents.vehicle.VehicleUpdatedV1;
import com.positivity.shopmanager.internal.entity.ExtCustomerPartyReplica;
import com.positivity.shopmanager.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.shopmanager.internal.entity.ExtVehicleReplica;
import com.positivity.shopmanager.internal.entity.ProcessedEvent;
import com.positivity.shopmanager.internal.repository.ExtCustomerPartyReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtPersonReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtStaffingAssignmentReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtVehicleReplicaRepository;
import com.positivity.shopmanager.internal.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
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
 * pos-shop-manager consumes four upstream owners into {@code ext_*} replicas and
 * reconciles three of them with manifest listeners (ADR-0044 §4/§6). Both
 * families are internally identical, so each family's contract is pinned once,
 * parameterized — the same shape used in pos-order, pos-people, and pos-invoice.
 *
 * <p>
 * Replica contract: unparseable messages and missing eventIds are skipped
 * without a dedup row; replays are no-ops; transient database errors are
 * rethrown for container retry; malformed payloads are swallowed but still
 * recorded, keeping this consumer's count aligned with the owner's manifest;
 * the stale guard skips only strictly-lower versions.
 *
 * <p>
 * Manifest contract: the drift verdict is stateless; drift increments the
 * metric and requests a replay naming the failed window on the owner's own
 * commands topic; an unparseable manifest is dropped; a failed replay publish
 * still counts the drift.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("pos-shop-manager replica and manifest listeners — shared contracts")
class ReplicaAndManifestListenerContractTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");
    private static final Instant WINDOW_START = Instant.parse("2026-08-11T09:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-08-11T09:05:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private ExtCustomerPartyReplicaRepository customerRepository;

    @Mock
    private ExtVehicleReplicaRepository vehicleRepository;

    @Mock
    private ExtStaffingAssignmentReplicaRepository assignmentRepository;

    @Mock
    private ExtPersonReplicaRepository personRepository;

    @Mock
    private com.positivity.shopmanager.internal.service.MechanicSyncService mechanicSyncService;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private ObjectProvider<MeterRegistry> meterRegistryProvider;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(customerRepository.findById(any())).thenReturn(Optional.empty());
        when(vehicleRepository.findById(any())).thenReturn(Optional.empty());
        when(assignmentRepository.findById(any())).thenReturn(Optional.empty());
        when(personRepository.findById(any())).thenReturn(Optional.empty());
    }

    // ---------- replica listeners ----------

    static final List<String> REPLICAS = List.of("customer", "vehicle", "people", "people-contact");

    private record Replica(
            String owner,
            String eventType,
            java.util.function.Function<String, String> payload,
            Consumer<String> dispatch,
            Runnable failHard) {}

    private Replica replica(String owner) {
        return switch (owner) {
            case "customer" ->
                new Replica(
                        CustomerEventsListener.OWNER,
                        CustomerPartyUpdatedV1.EVENT_TYPE,
                        id -> """
                            {"partyId":"%s","partyType":"ORGANIZATION","customerNumber":"C-1",
                             "displayName":"Fleet Co","status":"ACTIVE","requirementsMet":true}""".formatted(id),
                        new CustomerEventsListener(
                                clock,
                                objectMapper,
                                processedEventRepository,
                                customerRepository,
                                org.mockito.Mockito.mock(ObjectProvider.class))::onCustomerEvent,
                        () -> doThrow(new QueryTimeoutException("lock wait"))
                                .when(customerRepository)
                                .findById(any()));
            case "vehicle" ->
                new Replica(
                        VehicleEventsListener.OWNER,
                        VehicleUpdatedV1.EVENT_TYPE,
                        id -> """
                            {"vehicleId":"%s","accountId":"%s","vin":"1HGCM82633A004352",
                             "unitNumber":"U1","description":"d","licensePlate":"AB-123",
                             "year":2024,"make":"Toyota","model":"Tacoma","active":true}""".formatted(id, ID),
                        new VehicleEventsListener(
                                clock,
                                objectMapper,
                                processedEventRepository,
                                vehicleRepository,
                                org.mockito.Mockito.mock(ObjectProvider.class))::onVehicleEvent,
                        () -> doThrow(new QueryTimeoutException("lock wait"))
                                .when(vehicleRepository)
                                .findById(any()));
            case "people" ->
                new Replica(
                        PeopleEventsListener.OWNER,
                        StaffingAssignmentUpdatedV1.EVENT_TYPE,
                        id -> """
                            {"assignmentId":"%s","employeeId":"%s","personId":"%s","locationId":"%s",
                             "role":"TECHNICIAN","primary":true,"status":"ACTIVE",
                             "effectiveFrom":"2026-02-01","effectiveTo":null}""".formatted(id, ID, ID, ID),
                        new PeopleEventsListener(
                                clock,
                                objectMapper,
                                processedEventRepository,
                                assignmentRepository,
                                mechanicSyncService,
                                personRepository,
                                org.mockito.Mockito.mock(ObjectProvider.class))::onPeopleEvent,
                        () -> doThrow(new QueryTimeoutException("lock wait"))
                                .when(assignmentRepository)
                                .findById(any()));
            case "people-contact" ->
                new Replica(
                        PeopleContactEventsListener.OWNER,
                        PersonUpdatedV1.EVENT_TYPE,
                        id -> """
                            {"personId":"%s","firstName":"Ada","lastName":"Lovelace",
                             "preferredName":null,"contactPoints":[],"postalAddress":null,
                             "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-08-01T00:00:00Z"}""".formatted(id),
                        new PeopleContactEventsListener(
                                clock,
                                objectMapper,
                                processedEventRepository,
                                personRepository,
                                org.mockito.Mockito.mock(ObjectProvider.class))::onPeopleContactEvent,
                        () -> doThrow(new QueryTimeoutException("lock wait"))
                                .when(personRepository)
                                .findById(any()));
            default -> throw new IllegalArgumentException("Unknown replica owner in test fixture: " + owner);
        };
    }

    private String envelope(Replica replica, String eventId, String idValue) {
        return """
                {"eventId":"%s","eventType":"%s","aggregateVersion":3,"payload":%s}""".formatted(eventId, replica.eventType(), replica.payload().apply(idValue));
    }

    @Nested
    @DisplayName("replica listeners")
    class Replicas {

        @ParameterizedTest
        @FieldSource("com.positivity.shopmanager.internal.service.ReplicaAndManifestListenerContractTest#REPLICAS")
        @DisplayName("stamps its own owner on the dedup row after applying a fact")
        void happyPath(String owner) {
            Replica replica = replica(owner);

            replica.dispatch().accept(envelope(replica, "evt-1", ID.toString()));

            ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
            verify(processedEventRepository).save(captor.capture());
            assertThat(captor.getValue().getEventId()).isEqualTo("evt-1");
            assertThat(captor.getValue().getOwner()).isEqualTo(replica.owner());
            assertThat(captor.getValue().getProcessedAt()).isEqualTo(NOW);
        }

        @ParameterizedTest
        @FieldSource("com.positivity.shopmanager.internal.service.ReplicaAndManifestListenerContractTest#REPLICAS")
        @DisplayName("skips unparseable messages, missing eventIds, and replays")
        void deliveryGuards(String owner) {
            Replica replica = replica(owner);

            replica.dispatch().accept("{not json");
            replica.dispatch().accept(envelope(replica, "", ID.toString()));
            when(processedEventRepository.existsById("evt-1")).thenReturn(true);
            replica.dispatch().accept(envelope(replica, "evt-1", ID.toString()));

            verify(processedEventRepository, never()).save(any());
        }

        @ParameterizedTest
        @FieldSource("com.positivity.shopmanager.internal.service.ReplicaAndManifestListenerContractTest#REPLICAS")
        @DisplayName("rethrows a transient database error so the container retries")
        void transientErrorRethrown(String owner) {
            Replica replica = replica(owner);
            replica.failHard().run();

            assertThatThrownBy(() -> replica.dispatch().accept(envelope(replica, "evt-1", ID.toString())))
                    .isInstanceOf(QueryTimeoutException.class);

            verify(processedEventRepository, never()).save(any());
        }

        @ParameterizedTest
        @FieldSource("com.positivity.shopmanager.internal.service.ReplicaAndManifestListenerContractTest#REPLICAS")
        @DisplayName("swallows a malformed payload but still records it toward the manifest count")
        void malformedPayloadRecorded(String owner) {
            Replica replica = replica(owner);

            replica.dispatch().accept(envelope(replica, "evt-1", "not-a-uuid"));

            verify(processedEventRepository).save(any());
        }

        @Test
        @DisplayName("customer: maps the party and removes the row on deletion")
        void customerMapping() {
            CustomerEventsListener listener = new CustomerEventsListener(
                    clock,
                    objectMapper,
                    processedEventRepository,
                    customerRepository,
                    org.mockito.Mockito.mock(ObjectProvider.class));
            Replica replica = replica("customer");

            replica.dispatch().accept(envelope(replica, "evt-1", ID.toString()));

            ArgumentCaptor<ExtCustomerPartyReplica> captor = ArgumentCaptor.forClass(ExtCustomerPartyReplica.class);
            verify(customerRepository).save(captor.capture());
            assertThat(captor.getValue().getPartyId()).isEqualTo(ID);
            assertThat(captor.getValue().getCustomerNumber()).isEqualTo("C-1");
            assertThat(captor.getValue().getDisplayName()).isEqualTo("Fleet Co");

            listener.onCustomerEvent("""
                    {"eventId":"evt-2","eventType":"%s","payload":{"partyId":"%s"}}""".formatted(CustomerPartyDeletedV1.EVENT_TYPE, ID));
            verify(customerRepository).deleteById(ID);
        }

        @Test
        @DisplayName("vehicle: maps the identity fields the appointment board displays")
        void vehicleMapping() {
            Replica replica = replica("vehicle");

            replica.dispatch().accept(envelope(replica, "evt-1", ID.toString()));

            ArgumentCaptor<ExtVehicleReplica> captor = ArgumentCaptor.forClass(ExtVehicleReplica.class);
            verify(vehicleRepository).save(captor.capture());
            ExtVehicleReplica saved = captor.getValue();
            assertThat(saved.getVehicleId()).isEqualTo(ID);
            assertThat(saved.getVin()).isEqualTo("1HGCM82633A004352");
            assertThat(saved.getLicensePlate()).isEqualTo("AB-123");
            assertThat(saved.getMake()).isEqualTo("Toyota");
            assertThat(saved.isActive()).isTrue();
        }

        @Test
        @DisplayName("people: maps the staffing assignment scheduling reads")
        void staffingMapping() {
            Replica replica = replica("people");

            replica.dispatch().accept(envelope(replica, "evt-1", ID.toString()));

            ArgumentCaptor<ExtStaffingAssignmentReplica> captor =
                    ArgumentCaptor.forClass(ExtStaffingAssignmentReplica.class);
            verify(assignmentRepository).save(captor.capture());
            assertThat(captor.getValue().getAssignmentId()).isEqualTo(ID);
            assertThat(captor.getValue().getRole()).isEqualTo("TECHNICIAN");
            assertThat(captor.getValue().isPrimary()).isTrue();
        }

        @Test
        @DisplayName("stale guard: skips a strictly-older snapshot, re-applies an equal one")
        void staleGuard() {
            when(vehicleRepository.findById(ID))
                    .thenReturn(Optional.of(ExtVehicleReplica.builder()
                            .vehicleId(ID)
                            .aggregateVersion(3)
                            .build()));
            Replica replica = replica("vehicle");

            replica.dispatch()
                    .accept("""
                            {"eventId":"evt-1","eventType":"%s","aggregateVersion":2,"payload":%s}""".formatted(replica.eventType(), replica.payload().apply(ID.toString())));
            verify(vehicleRepository, never()).save(any());

            // Equal version re-applies: it is an emission-timestamp hint over a full snapshot.
            replica.dispatch().accept(envelope(replica, "evt-2", ID.toString()));
            verify(vehicleRepository).save(any());
        }
    }

    // ---------- manifest listeners ----------

    static final List<String> MANIFESTS = List.of("customer", "vehicle", "people-contact", "people");

    private record Manifest(String owner, String commandsTopic, Consumer<String> dispatch) {}

    private Manifest manifest(String owner) {
        switch (owner) {
            case "customer" -> {
                CustomerManifestListener listener = new CustomerManifestListener(
                        processedEventRepository, kafkaTemplate, objectMapper, meterRegistryProvider);
                ReflectionTestUtils.setField(listener, "customerCommandsTopic", "customer.commands.v1");
                return new Manifest(CustomerEventsListener.OWNER, "customer.commands.v1", listener::onManifest);
            }
            case "vehicle" -> {
                VehicleManifestListener listener = new VehicleManifestListener(
                        processedEventRepository, kafkaTemplate, objectMapper, meterRegistryProvider);
                ReflectionTestUtils.setField(listener, "vehicleCommandsTopic", "vehicle.commands.v1");
                return new Manifest(VehicleEventsListener.OWNER, "vehicle.commands.v1", listener::onManifest);
            }
            case "people-contact" -> {
                PeopleContactManifestListener listener = new PeopleContactManifestListener(
                        processedEventRepository, kafkaTemplate, objectMapper, meterRegistryProvider);
                ReflectionTestUtils.setField(listener, "peopleContactCommandsTopic", "people-contact.commands.v1");
                return new Manifest(
                        PeopleContactEventsListener.OWNER, "people-contact.commands.v1", listener::onManifest);
            }
            case "people" -> {
                PeopleManifestListener listener = new PeopleManifestListener(
                        processedEventRepository, kafkaTemplate, objectMapper, meterRegistryProvider);
                ReflectionTestUtils.setField(listener, "peopleCommandsTopic", "people.commands.v1");
                return new Manifest(PeopleEventsListener.OWNER, "people.commands.v1", listener::onManifest);
            }
            default -> throw new IllegalArgumentException("Unknown manifest owner in test fixture: " + owner);
        }
    }

    private String manifestMessage(long eventCount, String checksum) {
        return """
                {"eventId":"evt-1","eventType":"x.reconciliation.manifest",
                 "payload":{"windowStartUtc":"%s","windowEndUtc":"%s","eventCount":%d,
                   "eventIdsChecksum":"%s","eventTypeCounts":null}}
                """.formatted(WINDOW_START, WINDOW_END, eventCount, checksum);
    }

    private void replicaHolds(Manifest manifest, List<String> eventIds) {
        when(processedEventRepository.findEventIdsInRange(
                        manifest.owner(),
                        UuidV7Timestamps.minStringAt(WINDOW_START),
                        UuidV7Timestamps.minStringAt(WINDOW_END)))
                .thenReturn(eventIds);
    }

    private double driftCount(String owner) {
        return meterRegistry.find("replica.drift").tag("owner", owner).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }

    @Nested
    @DisplayName("manifest listeners")
    class Manifests {

        @ParameterizedTest
        @FieldSource("com.positivity.shopmanager.internal.service.ReplicaAndManifestListenerContractTest#MANIFESTS")
        @DisplayName("stays silent on a matching window")
        void matchingWindow(String owner) {
            Manifest manifest = manifest(owner);
            List<String> ids = List.of("019ff000-0000-7000-8000-000000000001");
            replicaHolds(manifest, ids);

            manifest.dispatch().accept(manifestMessage(1, ReconciliationManifestV1.checksumOf(ids)));

            verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
            assertThat(driftCount(manifest.owner())).isZero();
        }

        @ParameterizedTest
        @FieldSource("com.positivity.shopmanager.internal.service.ReplicaAndManifestListenerContractTest#MANIFESTS")
        @DisplayName("counts drift and requests a replay on its own owner's topic")
        void driftRequestsReplay(String owner) {
            Manifest manifest = manifest(owner);
            replicaHolds(manifest, List.of());

            manifest.dispatch().accept(manifestMessage(2, "owner-checksum"));

            assertThat(driftCount(manifest.owner())).isEqualTo(1.0);
            ArgumentCaptor<String> command = ArgumentCaptor.forClass(String.class);
            verify(kafkaTemplate)
                    .send(
                            org.mockito.ArgumentMatchers.eq(manifest.commandsTopic()),
                            org.mockito.ArgumentMatchers.eq(WINDOW_START.toString()),
                            command.capture());
            assertThat(objectMapper
                            .readTree(command.getValue())
                            .path("payload")
                            .path("since")
                            .asString())
                    .isEqualTo(WINDOW_START.toString());
        }

        @ParameterizedTest
        @FieldSource("com.positivity.shopmanager.internal.service.ReplicaAndManifestListenerContractTest#MANIFESTS")
        @DisplayName("treats a checksum mismatch at matching counts as drift, statelessly")
        void checksumMismatchAndStatelessness(String owner) {
            Manifest manifest = manifest(owner);
            List<String> ids = List.of("019ff000-0000-7000-8000-000000000001");
            replicaHolds(manifest, ids);
            String message = manifestMessage(1, "a-different-checksum");

            manifest.dispatch().accept(message);
            manifest.dispatch().accept(message);

            // Same verdict every time; a redelivered manifest costs one more idempotent replay.
            assertThat(driftCount(manifest.owner())).isEqualTo(2.0);
        }

        @ParameterizedTest
        @FieldSource("com.positivity.shopmanager.internal.service.ReplicaAndManifestListenerContractTest#MANIFESTS")
        @DisplayName("drops an unparseable manifest and survives a failed replay publish")
        void robustness(String owner) {
            Manifest manifest = manifest(owner);

            manifest.dispatch().accept("{not json");
            verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());

            replicaHolds(manifest, List.of());
            when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                    .thenThrow(new IllegalStateException("broker down"));

            manifest.dispatch().accept(manifestMessage(3, "owner-checksum"));

            // Metric still fired; the broker outage must not take the consumer down.
            assertThat(driftCount(manifest.owner())).isEqualTo(1.0);
        }
    }
}
