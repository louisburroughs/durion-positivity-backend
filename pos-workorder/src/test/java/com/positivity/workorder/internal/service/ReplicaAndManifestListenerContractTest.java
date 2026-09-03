package com.positivity.workorder.internal.service;

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
import com.positivity.domainevents.location.LocationUpdatedV1;
import com.positivity.domainevents.people.StaffingAssignmentUpdatedV1;
import com.positivity.domainevents.peoplecontact.PersonUpdatedV1;
import com.positivity.domainevents.peoplecontact.UserPersonLinkRemovedV1;
import com.positivity.domainevents.peoplecontact.UserPersonLinkUpdatedV1;
import com.positivity.workorder.internal.dto.location.BayDeletedV1;
import com.positivity.workorder.internal.dto.location.BayUpdatedV1;
import com.positivity.workorder.internal.dto.location.MobileUnitUpdatedV1;
import com.positivity.workorder.internal.entity.ExtBayReplica;
import com.positivity.workorder.internal.entity.ExtCustomerPartyReplica;
import com.positivity.workorder.internal.entity.ExtLocationReplica;
import com.positivity.workorder.internal.entity.ExtMobileUnitReplica;
import com.positivity.workorder.internal.entity.ExtPersonReplica;
import com.positivity.workorder.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.workorder.internal.entity.ExtUserLinkReplica;
import com.positivity.workorder.internal.entity.ProcessedEvent;
import com.positivity.workorder.internal.repository.ExtBayReplicaRepository;
import com.positivity.workorder.internal.repository.ExtCustomerPartyReplicaRepository;
import com.positivity.workorder.internal.repository.ExtLocationReplicaRepository;
import com.positivity.workorder.internal.repository.ExtMobileUnitReplicaRepository;
import com.positivity.workorder.internal.repository.ExtPersonReplicaRepository;
import com.positivity.workorder.internal.repository.ExtStaffingAssignmentReplicaRepository;
import com.positivity.workorder.internal.repository.ExtUserLinkReplicaRepository;
import com.positivity.workorder.internal.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
 * Unit tests for pos-workorder's replica and reconciliation listeners
 * (ADR-0044 §4/§6), same contract shape as the sibling modules.
 *
 * <p>
 * The distinctive piece here is {@link PeopleReplicaEventsListener}: one
 * component subscribed to <em>two</em> topics ({@code people-contact.events.v1}
 * and {@code people.events.v1}), stamping a different owner on the dedup row
 * depending on which entry point delivered the message. The owner column is
 * what the two manifest comparisons filter by, so a fact recorded under the
 * wrong owner would corrupt both windows at once — one count too high, the
 * other too low.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("pos-workorder replica and manifest listeners — shared contracts")
class ReplicaAndManifestListenerContractTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID SITE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");
    private static final Instant WINDOW_START = Instant.parse("2026-08-11T09:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-08-11T09:05:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private ExtPersonReplicaRepository personRepository;

    @Mock
    private ExtUserLinkReplicaRepository userLinkRepository;

    @Mock
    private ExtStaffingAssignmentReplicaRepository assignmentRepository;

    @Mock
    private ExtCustomerPartyReplicaRepository customerRepository;

    @Mock
    private ExtLocationReplicaRepository locationRepository;

    @Mock
    private ExtBayReplicaRepository bayRepository;

    @Mock
    private ExtMobileUnitReplicaRepository mobileUnitRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private ObjectProvider<MeterRegistry> meterRegistryProvider;

    private SimpleMeterRegistry meterRegistry;
    private PeopleReplicaEventsListener peopleListener;
    private CustomerEventsListener customerListener;
    private LocationEventsListener locationListener;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(personRepository.findById(any())).thenReturn(Optional.empty());
        when(userLinkRepository.findById(any())).thenReturn(Optional.empty());
        when(assignmentRepository.findById(any())).thenReturn(Optional.empty());
        when(customerRepository.findById(any())).thenReturn(Optional.empty());
        when(bayRepository.findById(any())).thenReturn(Optional.empty());
        when(mobileUnitRepository.findById(any())).thenReturn(Optional.empty());
        when(locationRepository.findById(any())).thenReturn(Optional.empty());
        peopleListener = new PeopleReplicaEventsListener(
                clock,
                objectMapper,
                processedEventRepository,
                personRepository,
                userLinkRepository,
                assignmentRepository,
                org.mockito.Mockito.mock(ObjectProvider.class));
        customerListener = new CustomerEventsListener(
                clock,
                objectMapper,
                processedEventRepository,
                customerRepository,
                org.mockito.Mockito.mock(ObjectProvider.class));
        locationListener = new LocationEventsListener(
                clock,
                objectMapper,
                processedEventRepository,
                locationRepository,
                bayRepository,
                mobileUnitRepository,
                org.mockito.Mockito.mock(ObjectProvider.class));
    }

    private static String envelope(String eventId, String eventType, String payload) {
        return """
                {"eventId":"%s","eventType":"%s","aggregateVersion":3,"payload":%s}""".formatted(eventId, eventType, payload);
    }

    private static String personPayload() {
        return """
                {"personId":"%s","firstName":"Ada","lastName":"Lovelace","preferredName":null,
                 "contactPoints":[],"postalAddress":null,
                 "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-08-01T00:00:00Z"}""".formatted(ID);
    }

    private ProcessedEvent capturedProcessedEvent() {
        ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("dual-topic people listener")
    class DualTopicOwnership {

        @Test
        @DisplayName("stamps owner people-contact on facts arriving through the people-contact entry point")
        void peopleContactEntryPoint() {
            peopleListener.onPeopleContactEvent(envelope("evt-1", PersonUpdatedV1.EVENT_TYPE, personPayload()));

            // The owner column is what each manifest comparison filters by; the wrong owner here
            // would corrupt both reconciliation windows at once.
            assertThat(capturedProcessedEvent().getOwner()).isEqualTo(PeopleReplicaEventsListener.OWNER_PEOPLE_CONTACT);
            verify(personRepository).save(any(ExtPersonReplica.class));
        }

        @Test
        @DisplayName("stamps owner people on facts arriving through the people entry point")
        void peopleEntryPoint() {
            peopleListener.onPeopleEvent(
                    envelope("evt-2", StaffingAssignmentUpdatedV1.EVENT_TYPE, """
                    {"assignmentId":"%s","employeeId":"%s","personId":"%s","locationId":"%s",
                     "role":"TECHNICIAN","primary":true,"status":"ACTIVE",
                     "effectiveFrom":"2026-02-01","effectiveTo":null}""".formatted(ID, ID, ID, ID)));

            assertThat(capturedProcessedEvent().getOwner()).isEqualTo(PeopleReplicaEventsListener.OWNER_PEOPLE);
            ArgumentCaptor<ExtStaffingAssignmentReplica> captor =
                    ArgumentCaptor.forClass(ExtStaffingAssignmentReplica.class);
            verify(assignmentRepository).save(captor.capture());
            assertThat(captor.getValue().getRole()).isEqualTo("TECHNICIAN");
        }

        @Test
        @DisplayName("maintains the user-link replica through update and removal")
        void userLinkLifecycle() {
            peopleListener.onPeopleContactEvent(
                    envelope("evt-3", UserPersonLinkUpdatedV1.EVENT_TYPE, """
                    {"linkId":"%s","personId":"%s","username":"ada","status":"ACTIVE",
                     "linkType":"PRIMARY","createdAt":"2026-01-01T00:00:00Z",
                     "updatedAt":"2026-08-01T00:00:00Z"}""".formatted(ID, ID)));

            ArgumentCaptor<ExtUserLinkReplica> captor = ArgumentCaptor.forClass(ExtUserLinkReplica.class);
            verify(userLinkRepository).save(captor.capture());
            assertThat(captor.getValue().getUsername()).isEqualTo("ada");

            peopleListener.onPeopleContactEvent(
                    envelope("evt-4", UserPersonLinkRemovedV1.EVENT_TYPE, """
                    {"linkId":"%s","personId":"%s","username":"ada"}""".formatted(ID, ID)));
            verify(userLinkRepository).deleteById(ID);
        }

        @Test
        @DisplayName("records an ignored type under the entry point's owner, keeping both manifests honest")
        void ignoredTypeRecordedPerEntryPoint() {
            peopleListener.onPeopleEvent("""
                    {"eventId":"evt-5","eventType":"people.employee.updated","payload":{}}""");

            assertThat(capturedProcessedEvent().getOwner()).isEqualTo(PeopleReplicaEventsListener.OWNER_PEOPLE);
        }

        @Test
        @DisplayName("shares one dedup log across both entry points")
        void dedupSharedAcrossEntryPoints() {
            when(processedEventRepository.existsById("evt-1")).thenReturn(true);

            peopleListener.onPeopleContactEvent(envelope("evt-1", PersonUpdatedV1.EVENT_TYPE, personPayload()));
            peopleListener.onPeopleEvent(envelope("evt-1", PersonUpdatedV1.EVENT_TYPE, personPayload()));

            verify(personRepository, never()).save(any());
            verify(processedEventRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("delivery contract")
    class DeliveryContract {

        @Test
        @DisplayName("skips unparseable messages and missing eventIds across all listeners")
        void deliveryGuards() {
            peopleListener.onPeopleContactEvent("{not json");
            customerListener.onCustomerEvent("{not json");
            locationListener.onLocationEvent("{not json");
            peopleListener.onPeopleContactEvent(envelope("", PersonUpdatedV1.EVENT_TYPE, personPayload()));

            verify(processedEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("rethrows a transient database error but swallows and records a malformed payload")
        void transientVersusMalformed() {
            doThrow(new QueryTimeoutException("lock wait"))
                    .when(personRepository)
                    .findById(any());

            assertThatThrownBy(() -> peopleListener.onPeopleContactEvent(
                            envelope("evt-1", PersonUpdatedV1.EVENT_TYPE, personPayload())))
                    .isInstanceOf(QueryTimeoutException.class);
            verify(processedEventRepository, never()).save(any());

            peopleListener.onPeopleContactEvent(
                    envelope("evt-2", PersonUpdatedV1.EVENT_TYPE, "{\"personId\":\"not-a-uuid\"}"));
            verify(processedEventRepository).save(any());
        }

        @Test
        @DisplayName("customer: maps the party gate fields and removes the row on deletion")
        void customerMapping() {
            customerListener.onCustomerEvent(envelope("evt-1", CustomerPartyUpdatedV1.EVENT_TYPE, """
                    {"partyId":"%s","partyType":"ORGANIZATION","displayName":"Fleet Co",
                     "status":"ACTIVE","requirementsMet":true}""".formatted(ID)));

            ArgumentCaptor<ExtCustomerPartyReplica> captor = ArgumentCaptor.forClass(ExtCustomerPartyReplica.class);
            verify(customerRepository).save(captor.capture());
            assertThat(captor.getValue().getPartyId()).isEqualTo(ID);
            // requirementsMet is the gate a workorder checks before work begins.
            assertThat(captor.getValue().isRequirementsMet()).isTrue();

            customerListener.onCustomerEvent(
                    envelope("evt-2", CustomerPartyDeletedV1.EVENT_TYPE, "{\"partyId\":\"%s\"}".formatted(ID)));
            verify(customerRepository).deleteById(ID);
        }

        @Test
        @DisplayName("location: keeps the address and applies the strict stale guard")
        void locationMappingAndStaleGuard() {
            String payload = """
                    {"locationId":"%s","name":"Main Shop","code":"SHOP-1","status":"OPEN",
                     "active":true,"locationType":"SHOP","hrLocationId":null,"timezone":"America/Chicago",
                     "addressLine1":"1 Main St","addressLine2":null,"city":"Austin","region":"TX",
                     "postalCode":"78701","country":"US","defaultStagingLocationId":null,
                     "defaultQuarantineLocationId":null,"parents":[],
                     "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-08-01T00:00:00Z"}""".formatted(ID);

            locationListener.onLocationEvent(envelope("evt-1", LocationUpdatedV1.EVENT_TYPE, payload));

            ArgumentCaptor<ExtLocationReplica> captor = ArgumentCaptor.forClass(ExtLocationReplica.class);
            verify(locationRepository).save(captor.capture());
            assertThat(captor.getValue().getCity()).isEqualTo("Austin");
            assertThat(captor.getValue().getAggregateVersion()).isEqualTo(3);

            when(locationRepository.findById(ID))
                    .thenReturn(Optional.of(ExtLocationReplica.builder()
                            .locationId(ID)
                            .aggregateVersion(5)
                            .build()));
            locationListener.onLocationEvent(envelope("evt-2", LocationUpdatedV1.EVENT_TYPE, payload));
            // Version 3 against a replica at 5: strictly older, skipped.
            verify(locationRepository, org.mockito.Mockito.times(1)).save(any());
        }

        @Test
        @DisplayName("#1656 bay: maps identity, site scope and active flag, and removes the row on deletion")
        void bayMapping() {
            locationListener.onLocationEvent(envelope("evt-1", BayUpdatedV1.EVENT_TYPE, """
                    {"bayId":"%s","locationId":"%s","name":"Front Bay 1","bayType":"GENERAL",
                     "status":"ACTIVE","active":true}""".formatted(ID, SITE_ID)));

            ArgumentCaptor<ExtBayReplica> captor = ArgumentCaptor.forClass(ExtBayReplica.class);
            verify(bayRepository).save(captor.capture());
            assertThat(captor.getValue().getBayId()).isEqualTo(ID);
            assertThat(captor.getValue().getLocationId()).isEqualTo(SITE_ID);
            assertThat(captor.getValue().getName()).isEqualTo("Front Bay 1");
            assertThat(captor.getValue().isActive()).isTrue();
            assertThat(captor.getValue().getAggregateVersion()).isEqualTo(3);

            locationListener.onLocationEvent(envelope("evt-2", BayDeletedV1.EVENT_TYPE, """
                    {"bayId":"%s"}""".formatted(ID)));
            verify(bayRepository).deleteById(ID);
        }

        @Test
        @DisplayName("#1656 mobile unit: maps identity and base site, and honours the stale-version guard")
        void mobileUnitMapping() {
            String payload = """
                    {"mobileUnitId":"%s","baseLocationId":"%s","name":"Van 3","status":"ACTIVE","active":true}""".formatted(ID, SITE_ID);
            locationListener.onLocationEvent(envelope("evt-1", MobileUnitUpdatedV1.EVENT_TYPE, payload));

            ArgumentCaptor<ExtMobileUnitReplica> captor = ArgumentCaptor.forClass(ExtMobileUnitReplica.class);
            verify(mobileUnitRepository).save(captor.capture());
            assertThat(captor.getValue().getMobileUnitId()).isEqualTo(ID);
            assertThat(captor.getValue().getBaseLocationId()).isEqualTo(SITE_ID);
            assertThat(captor.getValue().getName()).isEqualTo("Van 3");
            assertThat(captor.getValue().isActive()).isTrue();

            when(mobileUnitRepository.findById(ID))
                    .thenReturn(Optional.of(ExtMobileUnitReplica.builder()
                            .mobileUnitId(ID)
                            .aggregateVersion(5)
                            .build()));
            locationListener.onLocationEvent(envelope("evt-2", MobileUnitUpdatedV1.EVENT_TYPE, payload));
            // Version 3 against a replica at 5: strictly older, skipped.
            verify(mobileUnitRepository, org.mockito.Mockito.times(1)).save(any());
        }

        @Test
        @DisplayName("#1656: an unknown location-domain fact is ignored but still recorded for the manifest")
        void unknownFactTypeIsRecordedNotApplied() {
            // pos-location does not publish bay/mobile-unit facts yet, and publishes storage-location
            // facts this module ignores. Neither may break the consumer or skew the manifest window.
            locationListener.onLocationEvent(envelope("evt-1", "location.storage-location.updated", "{}"));

            verify(bayRepository, never()).save(any());
            verify(mobileUnitRepository, never()).save(any());
            assertThat(capturedProcessedEvent().getOwner()).isEqualTo("location");
        }
    }

    @Nested
    @DisplayName("manifest listeners")
    class Manifests {

        private String manifestMessage(long eventCount, String checksum) {
            return """
                    {"eventId":"evt-1","eventType":"x.reconciliation.manifest",
                     "payload":{"windowStartUtc":"%s","windowEndUtc":"%s","eventCount":%d,
                       "eventIdsChecksum":"%s","eventTypeCounts":null}}
                    """.formatted(WINDOW_START, WINDOW_END, eventCount, checksum);
        }

        private double driftCount(String owner) {
            return meterRegistry.find("replica.drift").tag("owner", owner).counters().stream()
                    .mapToDouble(io.micrometer.core.instrument.Counter::count)
                    .sum();
        }

        @Test
        @DisplayName("customer manifest: silent on match, drift and replay on mismatch")
        void customerManifest() {
            CustomerManifestListener listener = new CustomerManifestListener(
                    processedEventRepository, kafkaTemplate, objectMapper, meterRegistryProvider);
            ReflectionTestUtils.setField(listener, "customerCommandsTopic", "customer.commands.v1");
            List<String> ids = List.of("019ff000-0000-7000-8000-000000000001");
            when(processedEventRepository.findEventIdsInRange(
                            CustomerEventsListener.OWNER,
                            UuidV7Timestamps.minStringAt(WINDOW_START),
                            UuidV7Timestamps.minStringAt(WINDOW_END)))
                    .thenReturn(ids);

            listener.onManifest(manifestMessage(1, ReconciliationManifestV1.checksumOf(ids)));
            verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());

            listener.onManifest(manifestMessage(2, "owner-checksum"));
            assertThat(driftCount("customer")).isEqualTo(1.0);
            verify(kafkaTemplate)
                    .send(
                            org.mockito.ArgumentMatchers.eq("customer.commands.v1"),
                            org.mockito.ArgumentMatchers.eq(WINDOW_START.toString()),
                            anyString());
        }

        @Test
        @DisplayName("location manifest: drops an unparseable manifest, survives a failed replay publish")
        void locationManifestRobustness() {
            LocationManifestListener listener = new LocationManifestListener(
                    processedEventRepository, kafkaTemplate, objectMapper, meterRegistryProvider);
            ReflectionTestUtils.setField(listener, "locationCommandsTopic", "location.commands.v1");

            listener.onManifest("{not json");
            verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());

            when(processedEventRepository.findEventIdsInRange(anyString(), anyString(), anyString()))
                    .thenReturn(List.of());
            when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                    .thenThrow(new IllegalStateException("broker down"));

            listener.onManifest(manifestMessage(3, "owner-checksum"));

            assertThat(driftCount("location")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("people manifest: silent on match, drift and replay on mismatch — closes the loop for the"
                + " staffing-assignment half of the dual-topic people listener")
        void peopleManifest() {
            PeopleManifestListener listener = new PeopleManifestListener(
                    processedEventRepository, kafkaTemplate, objectMapper, meterRegistryProvider);
            ReflectionTestUtils.setField(listener, "peopleCommandsTopic", "people.commands.v1");
            List<String> ids = List.of("019ff000-0000-7000-8000-000000000001");
            when(processedEventRepository.findEventIdsInRange(
                            PeopleReplicaEventsListener.OWNER_PEOPLE,
                            UuidV7Timestamps.minStringAt(WINDOW_START),
                            UuidV7Timestamps.minStringAt(WINDOW_END)))
                    .thenReturn(ids);

            listener.onManifest(manifestMessage(1, ReconciliationManifestV1.checksumOf(ids)));
            verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());

            listener.onManifest(manifestMessage(2, "owner-checksum"));
            assertThat(driftCount("people")).isEqualTo(1.0);
            verify(kafkaTemplate)
                    .send(
                            org.mockito.ArgumentMatchers.eq("people.commands.v1"),
                            org.mockito.ArgumentMatchers.eq(WINDOW_START.toString()),
                            anyString());
        }
    }
}
