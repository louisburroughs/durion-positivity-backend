package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.customer.CustomerPartyDeletedV1;
import com.positivity.domainevents.customer.CustomerPartyUpdatedV1;
import com.positivity.domainevents.location.LocationDeletedV1;
import com.positivity.domainevents.location.LocationUpdatedV1;
import com.positivity.domainevents.people.EmployeeUpdatedV1;
import com.positivity.domainevents.workorder.WorkorderUpdatedV1;
import com.positivity.invoice.internal.entity.ExtCustomerPartyReplica;
import com.positivity.invoice.internal.entity.ExtEmployeeReplica;
import com.positivity.invoice.internal.entity.ExtLocationReplica;
import com.positivity.invoice.internal.entity.ExtWorkorderReplica;
import com.positivity.invoice.internal.entity.ProcessedEvent;
import com.positivity.invoice.internal.repository.ExtCustomerPartyReplicaRepository;
import com.positivity.invoice.internal.repository.ExtEmployeeReplicaRepository;
import com.positivity.invoice.internal.repository.ExtLocationReplicaRepository;
import com.positivity.invoice.internal.repository.ExtWorkorderReplicaRepository;
import com.positivity.invoice.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import tools.jackson.databind.ObjectMapper;

/**
 * pos-invoice keeps four {@code ext_*} replicas, one per upstream owner, and all
 * four listeners implement the same consumer contract (ADR-0044 §6). It is pinned
 * once here so a listener cannot quietly drop half of it, and the per-owner field
 * mapping is covered in the mapping tests below.
 *
 * <p>
 * The contract:
 *
 * <ul>
 * <li><b>An unparseable message or an envelope with no {@code eventId} is
 * skipped without a dedup row</b> — there is nothing to de-duplicate it by, and
 * recording it would mask a later genuine delivery of the same id.</li>
 * <li><b>A replayed {@code eventId} is a no-op</b>, which is what makes
 * at-least-once delivery safe.</li>
 * <li><b>A transient database error is rethrown</b> so the container retries and
 * can reach the DLQ; swallowing it would leave the replica permanently behind
 * with nothing to signal it.</li>
 * <li><b>A malformed payload is swallowed but still recorded.</b> A poison
 * message must not wedge the partition, and the row keeps this consumer's count
 * aligned with the owner's manifest, which counts every fact in the
 * window.</li>
 * <li><b>The stale guard skips only strictly-lower versions.</b> The producer's
 * {@code aggregateVersion} is an emission-timestamp hint, so an equal version
 * re-applies a full snapshot rather than risking a dropped update.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("pos-invoice ext_* replica listeners — shared consumer contract")
class ReplicaListenerContractTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private ExtCustomerPartyReplicaRepository customerRepository;

    @Mock
    private ExtLocationReplicaRepository locationRepository;

    @Mock
    private ExtEmployeeReplicaRepository employeeRepository;

    @Mock
    private ExtWorkorderReplicaRepository workorderRepository;

    static final List<String> LISTENERS = List.of("customer", "location", "people", "workorder");

    /** One listener under test: how to feed it, what it replicates, and how to make it fail hard. */
    private record Listener(
            String owner,
            String eventType,
            java.util.function.Function<String, String> payload,
            Consumer<String> dispatch,
            Runnable failHard) {}

    @BeforeEach
    void setUp() {
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(customerRepository.findById(any())).thenReturn(Optional.empty());
        when(locationRepository.findById(any())).thenReturn(Optional.empty());
        when(employeeRepository.findById(any())).thenReturn(Optional.empty());
        when(workorderRepository.findById(any())).thenReturn(Optional.empty());
    }

    private Listener listener(String owner) {
        return switch (owner) {
            case "customer" ->
                new Listener(
                        CustomerEventsListener.OWNER,
                        CustomerPartyUpdatedV1.EVENT_TYPE,
                        id -> """
                            {"partyId":"%s","partyType":"ORGANIZATION","displayName":"Fleet Co",
                             "status":"ACTIVE","requirementsMet":true}""".formatted(id),
                        new CustomerEventsListener(
                                clock,
                                objectMapper,
                                processedEventRepository,
                                customerRepository,
                                org.mockito.Mockito.mock(ObjectProvider.class))::onCustomerEvent,
                        () -> doThrow(new QueryTimeoutException("lock wait"))
                                .when(customerRepository)
                                .findById(any()));
            case "location" ->
                new Listener(
                        LocationEventsListener.OWNER,
                        LocationUpdatedV1.EVENT_TYPE,
                        id -> """
                            {"locationId":"%s","name":"Main Shop","active":true}""".formatted(id),
                        new LocationEventsListener(
                                clock,
                                objectMapper,
                                processedEventRepository,
                                locationRepository,
                                org.mockito.Mockito.mock(ObjectProvider.class))::onLocationEvent,
                        () -> doThrow(new QueryTimeoutException("lock wait"))
                                .when(locationRepository)
                                .findById(any()));
            case "people" ->
                new Listener(
                        PeopleEventsListener.OWNER,
                        EmployeeUpdatedV1.EVENT_TYPE,
                        id -> """
                            {"employeeId":"%s","employeeNumber":"E-1001","status":"ACTIVE"}""".formatted(id),
                        new PeopleEventsListener(
                                clock,
                                objectMapper,
                                processedEventRepository,
                                employeeRepository,
                                org.mockito.Mockito.mock(ObjectProvider.class))::onPeopleEvent,
                        () -> doThrow(new QueryTimeoutException("lock wait"))
                                .when(employeeRepository)
                                .findById(any()));
            default ->
                new Listener(
                        WorkorderEventsListener.OWNER,
                        WorkorderUpdatedV1.EVENT_TYPE,
                        id -> """
                            {"workorderId":"%s","workorderNumber":"WO-1001","status":"CLOSED"}""".formatted(id),
                        new WorkorderEventsListener(
                                clock,
                                objectMapper,
                                processedEventRepository,
                                workorderRepository,
                                org.mockito.Mockito.mock(ObjectProvider.class))::onWorkorderEvent,
                        () -> doThrow(new QueryTimeoutException("lock wait"))
                                .when(workorderRepository)
                                .findById(any()));
        };
    }

    private String envelope(Listener listener, String eventId, String idValue) {
        return """
                {"eventId":"%s","eventType":"%s","aggregateVersion":3,"payload":%s}""".formatted(eventId, listener.eventType(), listener.payload().apply(idValue));
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("stamps its own owner on the dedup row after applying a well-formed fact")
    void happyPathRecordsProcessedEvent(String owner) {
        Listener listener = listener(owner);

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
    void unparsableMessageIsSkipped(String owner) {
        listener(owner).dispatch().accept("{not json");

        verify(processedEventRepository, never()).save(any());
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("skips an envelope with no eventId, since nothing could de-duplicate it")
    void missingEventIdIsSkipped(String owner) {
        Listener listener = listener(owner);

        listener.dispatch().accept(envelope(listener, "", ID.toString()));

        verify(processedEventRepository, never()).save(any());
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("no-ops on a replayed eventId")
    void replayIsIgnored(String owner) {
        when(processedEventRepository.existsById("evt-1")).thenReturn(true);
        Listener listener = listener(owner);

        listener.dispatch().accept(envelope(listener, "evt-1", ID.toString()));

        verify(processedEventRepository, never()).save(any());
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("rethrows a transient database error so the container retries instead of losing the fact")
    void transientDatabaseErrorIsRethrown(String owner) {
        Listener listener = listener(owner);
        listener.failHard().run();

        assertThatThrownBy(() -> listener.dispatch().accept(envelope(listener, "evt-1", ID.toString())))
                .isInstanceOf(QueryTimeoutException.class);

        verify(processedEventRepository, never()).save(any());
    }

    @ParameterizedTest
    @FieldSource("LISTENERS")
    @DisplayName("swallows a malformed payload but still records it, keeping the manifest count aligned")
    void malformedPayloadIsSwallowedAndRecorded(String owner) {
        Listener listener = listener(owner);

        listener.dispatch().accept(envelope(listener, "evt-1", "not-a-uuid"));

        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("customer: replicates the party fields invoicing needs, and removes the row on deletion")
    void customerMapping() {
        CustomerEventsListener listener = new CustomerEventsListener(
                clock,
                objectMapper,
                processedEventRepository,
                customerRepository,
                org.mockito.Mockito.mock(ObjectProvider.class));

        listener.onCustomerEvent("""
                {"eventId":"evt-1","eventType":"%s","aggregateVersion":3,
                 "payload":{"partyId":"%s","partyType":"ORGANIZATION","displayName":"Fleet Co",
                   "status":"ACTIVE","requirementsMet":true}}""".formatted(CustomerPartyUpdatedV1.EVENT_TYPE, ID));

        ArgumentCaptor<ExtCustomerPartyReplica> captor = ArgumentCaptor.forClass(ExtCustomerPartyReplica.class);
        verify(customerRepository).save(captor.capture());
        assertThat(captor.getValue().getPartyId()).isEqualTo(ID);
        assertThat(captor.getValue().getPartyType()).isEqualTo("ORGANIZATION");
        assertThat(captor.getValue().getDisplayName()).isEqualTo("Fleet Co");
        assertThat(captor.getValue().getAggregateVersion()).isEqualTo(3);
        assertThat(captor.getValue().getUpdatedAt()).isEqualTo(NOW);

        listener.onCustomerEvent("""
                {"eventId":"evt-2","eventType":"%s","payload":{"partyId":"%s"}}""".formatted(CustomerPartyDeletedV1.EVENT_TYPE, ID));
        verify(customerRepository).deleteById(ID);
    }

    @Test
    @DisplayName("location: replicates the full address an invoice is stamped with")
    void locationMapping() {
        LocationEventsListener listener = new LocationEventsListener(
                clock,
                objectMapper,
                processedEventRepository,
                locationRepository,
                org.mockito.Mockito.mock(ObjectProvider.class));

        listener.onLocationEvent("""
                {"eventId":"evt-1","eventType":"%s","aggregateVersion":2,
                 "payload":{"locationId":"%s","name":"Main Shop","code":"SHOP-1","status":"OPEN",
                   "active":true,"locationType":"SHOP","hrLocationId":null,"timezone":"America/Chicago",
                   "addressLine1":"1 Main St","addressLine2":"Suite 2","city":"Austin","region":"TX",
                   "postalCode":"78701","country":"US","defaultStagingLocationId":null,
                   "defaultQuarantineLocationId":null,"parents":[],
                   "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-08-01T00:00:00Z"}}""".formatted(LocationUpdatedV1.EVENT_TYPE, ID));

        ArgumentCaptor<ExtLocationReplica> captor = ArgumentCaptor.forClass(ExtLocationReplica.class);
        verify(locationRepository).save(captor.capture());
        ExtLocationReplica saved = captor.getValue();
        // The whole address is kept: an invoice reprint has to reproduce what was billed.
        assertThat(saved.getAddressLine1()).isEqualTo("1 Main St");
        assertThat(saved.getAddressLine2()).isEqualTo("Suite 2");
        assertThat(saved.getCity()).isEqualTo("Austin");
        assertThat(saved.getRegion()).isEqualTo("TX");
        assertThat(saved.getPostalCode()).isEqualTo("78701");
        assertThat(saved.getCountry()).isEqualTo("US");
        assertThat(saved.getTimezone()).isEqualTo("America/Chicago");
        assertThat(saved.isActive()).isTrue();

        listener.onLocationEvent("""
                {"eventId":"evt-2","eventType":"%s","payload":{"locationId":"%s"}}""".formatted(LocationDeletedV1.EVENT_TYPE, ID));
        verify(locationRepository).deleteById(ID);
    }

    @Test
    @DisplayName("people: replicates the employee identity an invoice attributes work to")
    void peopleMapping() {
        UUID personId = UUID.randomUUID();
        new PeopleEventsListener(
                        clock,
                        objectMapper,
                        processedEventRepository,
                        employeeRepository,
                        org.mockito.Mockito.mock(ObjectProvider.class))
                .onPeopleEvent("""
                        {"eventId":"evt-1","eventType":"%s","aggregateVersion":1,
                         "payload":{"employeeId":"%s","personId":"%s","employeeNumber":"E-1001",
                           "status":"ACTIVE","hireDate":"2026-01-15","terminationDate":null,
                           "statusEffectiveAt":"2026-01-15T00:00:00Z"}}""".formatted(EmployeeUpdatedV1.EVENT_TYPE, ID, personId));

        ArgumentCaptor<ExtEmployeeReplica> captor = ArgumentCaptor.forClass(ExtEmployeeReplica.class);
        verify(employeeRepository).save(captor.capture());
        assertThat(captor.getValue().getEmployeeId()).isEqualTo(ID);
        assertThat(captor.getValue().getPersonId()).isEqualTo(personId);
        assertThat(captor.getValue().getEmployeeNumber()).isEqualTo("E-1001");
        assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("people: records but does not replicate an event type it does not consume")
    void peopleIgnoresOtherTypes() {
        new PeopleEventsListener(
                        clock,
                        objectMapper,
                        processedEventRepository,
                        employeeRepository,
                        org.mockito.Mockito.mock(ObjectProvider.class))
                .onPeopleEvent("""
                        {"eventId":"evt-9","eventType":"people.staffing-assignment.updated","payload":{}}""");

        // The replica is untouched, but the fact is still recorded: the owner's manifest counts
        // every event on the topic, so skipping the dedup row here would look like permanent
        // drift and trigger a replay that can never reconcile (#1537).
        verify(employeeRepository, never()).save(any());
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("workorder: replicates the workorder an invoice is raised from")
    void workorderMapping() {
        UUID customerId = UUID.randomUUID();
        new WorkorderEventsListener(
                        clock,
                        objectMapper,
                        processedEventRepository,
                        workorderRepository,
                        org.mockito.Mockito.mock(ObjectProvider.class))
                .onWorkorderEvent("""
                        {"eventId":"evt-1","eventType":"%s","aggregateVersion":6,
                         "payload":{"workorderId":"%s","workorderNumber":"WO-1001","status":"CLOSED",
                           "customerId":"%s","vehicleId":null,"shopId":null,"invoiceId":null,
                           "parts":[],"services":[]}}""".formatted(WorkorderUpdatedV1.EVENT_TYPE, ID, customerId));

        ArgumentCaptor<ExtWorkorderReplica> captor = ArgumentCaptor.forClass(ExtWorkorderReplica.class);
        verify(workorderRepository).save(captor.capture());
        assertThat(captor.getValue().getWorkorderId()).isEqualTo(ID);
        assertThat(captor.getValue().getWorkorderNumber()).isEqualTo("WO-1001");
        assertThat(captor.getValue().getStatus()).isEqualTo("CLOSED");
        assertThat(captor.getValue().getCustomerId()).isEqualTo(customerId);
        assertThat(captor.getValue().getAggregateVersion()).isEqualTo(6);
    }

    @Test
    @DisplayName("ignores a snapshot strictly older than the replica but re-applies an equal one")
    void staleGuardIsStrict() {
        when(workorderRepository.findById(ID))
                .thenReturn(Optional.of(ExtWorkorderReplica.builder()
                        .workorderId(ID)
                        .aggregateVersion(6)
                        .build()));
        WorkorderEventsListener listener = new WorkorderEventsListener(
                clock,
                objectMapper,
                processedEventRepository,
                workorderRepository,
                org.mockito.Mockito.mock(ObjectProvider.class));
        String template = """
                {"eventId":"evt-%d","eventType":"%s","aggregateVersion":%d,
                 "payload":{"workorderId":"%s","workorderNumber":"WO-1001","status":"CLOSED",
                   "customerId":null,"vehicleId":null,"shopId":null,"invoiceId":null,
                   "parts":[],"services":[]}}""";

        listener.onWorkorderEvent(template.formatted(1, WorkorderUpdatedV1.EVENT_TYPE, 5, ID));
        verify(workorderRepository, never()).save(any());

        // Equal versions re-apply: the version is an emission-timestamp hint and the payload is a
        // full snapshot, so re-applying costs nothing and never drops a real update.
        listener.onWorkorderEvent(template.formatted(2, WorkorderUpdatedV1.EVENT_TYPE, 6, ID));
        verify(workorderRepository).save(any());
    }
}
