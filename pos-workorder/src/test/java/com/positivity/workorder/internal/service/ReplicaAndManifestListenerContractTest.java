package com.positivity.workorder.internal.service;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.ReconciliationManifestV1;
import com.positivity.domainevents.UuidV7Timestamps;
import com.positivity.domainevents.customer.CustomerPartyDeletedV1;
import com.positivity.domainevents.customer.CustomerPartyUpdatedV1;
import com.positivity.domainevents.location.BayDeletedV1;
import com.positivity.domainevents.location.BaySpecialtyMapUpdatedV1;
import com.positivity.domainevents.location.BayUpdatedV1;
import com.positivity.domainevents.location.LocationUpdatedV1;
import com.positivity.domainevents.location.MobileUnitDeletedV1;
import com.positivity.domainevents.location.MobileUnitUpdatedV1;
import com.positivity.domainevents.people.EmployeeUpdatedV1;
import com.positivity.domainevents.people.PersonCredentialUpdatedV1;
import com.positivity.domainevents.people.StaffingAssignmentUpdatedV1;
import com.positivity.domainevents.peoplecontact.PersonUpdatedV1;
import com.positivity.domainevents.peoplecontact.UserPersonLinkRemovedV1;
import com.positivity.domainevents.peoplecontact.UserPersonLinkUpdatedV1;
import com.positivity.tenancy.kafka.TenantKafkaHeaders;
import com.positivity.workorder.internal.entity.ExtBayReplica;
import com.positivity.workorder.internal.entity.ExtBaySpecialtyMapReplica;
import com.positivity.workorder.internal.entity.ExtBayTypeReplica;
import com.positivity.workorder.internal.entity.ExtCustomerPartyReplica;
import com.positivity.workorder.internal.entity.ExtEmployeeReplica;
import com.positivity.workorder.internal.entity.ExtLocationReplica;
import com.positivity.workorder.internal.entity.ExtMobileUnitReplica;
import com.positivity.workorder.internal.entity.ExtPersonCredentialReplica;
import com.positivity.workorder.internal.entity.ExtPersonReplica;
import com.positivity.workorder.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.workorder.internal.entity.ExtUserLinkReplica;
import com.positivity.workorder.internal.entity.ProcessedEvent;
import com.positivity.workorder.internal.repository.ExtBayReplicaRepository;
import com.positivity.workorder.internal.repository.ExtCustomerPartyReplicaRepository;
import com.positivity.workorder.internal.repository.ExtEmployeeReplicaRepository;
import com.positivity.workorder.internal.repository.ExtLocationParentReplicaRepository;
import com.positivity.workorder.internal.repository.ExtLocationReplicaRepository;
import com.positivity.workorder.internal.repository.ExtMobileUnitReplicaRepository;
import com.positivity.workorder.internal.repository.ExtPersonCredentialReplicaRepository;
import com.positivity.workorder.internal.repository.ExtPersonReplicaRepository;
import com.positivity.workorder.internal.repository.ExtStaffingAssignmentReplicaRepository;
import com.positivity.workorder.internal.repository.ExtUserLinkReplicaRepository;
import com.positivity.workorder.internal.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.apache.kafka.clients.producer.ProducerRecord;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
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
    private static final UUID OTHER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e3");
    private static final UUID THIRD_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e4");
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
    private ExtEmployeeReplicaRepository employeeRepository;

    @Mock
    private ExtPersonCredentialReplicaRepository credentialRepository;

    @Mock
    private ExtCustomerPartyReplicaRepository customerRepository;

    @Mock
    private ExtLocationReplicaRepository locationRepository;

    @Mock
    private ExtLocationParentReplicaRepository locationParentRepository;

    @Mock
    private LocationHierarchyService locationHierarchyService;

    @Mock
    private ExtBayReplicaRepository bayRepository;

    @Mock
    private ExtMobileUnitReplicaRepository mobileUnitRepository;

    @Mock
    private com.positivity.workorder.internal.repository.ExtBaySpecialtyMapReplicaRepository baySpecialtyMapRepository;

    @Mock
    private com.positivity.workorder.internal.repository.ExtBayTypeReplicaRepository bayTypeRepository;

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
        when(employeeRepository.findById(any())).thenReturn(Optional.empty());
        when(credentialRepository.findById(any())).thenReturn(Optional.empty());
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
                employeeRepository,
                credentialRepository,
                org.mockito.Mockito.mock(ObjectProvider.class),
                org.mockito.Mockito.mock(PlatformTransactionManager.class));
        customerListener = new CustomerEventsListener(
                clock,
                objectMapper,
                processedEventRepository,
                customerRepository,
                org.mockito.Mockito.mock(ObjectProvider.class),
                org.mockito.Mockito.mock(PlatformTransactionManager.class));
        // The real registry, not a null provider: the rejection counter is the only signal that a
        // bay or mobile-unit fact arrived in a shape this module cannot use (#1668), so it has to be
        // assertable here.
        locationListener = new LocationEventsListener(
                clock,
                objectMapper,
                processedEventRepository,
                locationRepository,
                locationParentRepository,
                locationHierarchyService,
                bayRepository,
                mobileUnitRepository,
                baySpecialtyMapRepository,
                bayTypeRepository,
                meterRegistryProvider,
                org.mockito.Mockito.mock(PlatformTransactionManager.class));
    }

    private static String envelope(String eventId, String eventType, String payload) {
        return envelope(eventId, eventType, 3, payload);
    }

    private static String envelope(String eventId, String eventType, long aggregateVersion, String payload) {
        return """
                {"eventId":"%s","eventType":"%s","aggregateVersion":%d,"payload":%s}""".formatted(eventId, eventType, aggregateVersion, payload);
    }

    private static String personPayload() {
        return """
                {"personId":"%s","firstName":"Ada","lastName":"Lovelace","preferredName":null,
                 "contactPoints":[],"postalAddress":null,
                 "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-08-01T00:00:00Z"}""".formatted(ID);
    }

    private static String employeePayload(String status) {
        return """
                {"employeeId":"%s","personId":"%s","employeeNumber":"E-100","status":"%s",
                 "hireDate":"2024-01-02","terminationDate":"2026-08-14",
                 "statusEffectiveAt":"2026-08-15T00:00:00Z"}""".formatted(ID, OTHER_ID, status);
    }

    private static String credentialPayload(String status) {
        return """
                {"credentialId":"%s","personId":"%s","skillId":"%s","skillCode":"BRAKES-LIGHT",
                 "competenceCode":"BRAKES","minGvwrClass":1,"maxGvwrClass":3,"issuer":"ASE",
                 "sourceCode":"ASE","sourceCredentialCode":"A5-BRAKES","issuedOn":"2024-01-02",
                 "expiresOn":"2029-01-02","proficiency":4,"status":"%s","evidenceRef":null,
                 "supersededBy":null}""".formatted(ID, OTHER_ID, ID, status);
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
        @DisplayName("#2119 employee: upserts the employment-status replica and writes the processed mark")
        void employeeUpdatedUpserts() {
            peopleListener.onPeopleEvent(
                    envelope("evt-6", EmployeeUpdatedV1.EVENT_TYPE, employeePayload("TERMINATED")));

            ArgumentCaptor<ExtEmployeeReplica> captor = ArgumentCaptor.forClass(ExtEmployeeReplica.class);
            verify(employeeRepository).save(captor.capture());
            ExtEmployeeReplica saved = captor.getValue();
            assertThat(saved.getEmployeeId()).isEqualTo(ID);
            assertThat(saved.getPersonId()).isEqualTo(OTHER_ID);
            assertThat(saved.getStatus()).isEqualTo("TERMINATED");
            assertThat(saved.getStatusEffectiveAt()).isEqualTo(Instant.parse("2026-08-15T00:00:00Z"));
            assertThat(saved.getTerminationDate()).isEqualTo(java.time.LocalDate.parse("2026-08-14"));
            assertThat(saved.getAggregateVersion()).isEqualTo(3);
            ProcessedEvent mark = capturedProcessedEvent();
            assertThat(mark.getEventId()).isEqualTo("evt-6");
            assertThat(mark.getOwner()).isEqualTo(PeopleReplicaEventsListener.OWNER_PEOPLE);
        }

        @Test
        @DisplayName("#2119 employee: skips a strictly stale version, applies an equal one, still writes the mark")
        void employeeUpdatedStaleGuard() {
            when(employeeRepository.findById(ID))
                    .thenReturn(Optional.of(ExtEmployeeReplica.builder()
                            .employeeId(ID)
                            .personId(OTHER_ID)
                            .status("ACTIVE")
                            .aggregateVersion(5)
                            .build()));

            peopleListener.onPeopleEvent(
                    envelope("evt-7", EmployeeUpdatedV1.EVENT_TYPE, employeePayload("TERMINATED")));

            // Version 3 against a replica at 5: strictly older, skipped, but the fact is recorded.
            verify(employeeRepository, never()).save(any());
            assertThat(capturedProcessedEvent().getEventId()).isEqualTo("evt-7");

            peopleListener.onPeopleEvent(
                    envelope("evt-8", EmployeeUpdatedV1.EVENT_TYPE, 5, employeePayload("SUSPENDED")));
            ArgumentCaptor<ExtEmployeeReplica> captor = ArgumentCaptor.forClass(ExtEmployeeReplica.class);
            verify(employeeRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo("SUSPENDED");
        }

        @Test
        @DisplayName("#2122 credential: upserts the credential replica and writes the processed mark")
        void credentialUpdatedUpserts() {
            peopleListener.onPeopleEvent(
                    envelope("evt-9", PersonCredentialUpdatedV1.EVENT_TYPE, credentialPayload("ACTIVE")));

            ArgumentCaptor<ExtPersonCredentialReplica> captor =
                    ArgumentCaptor.forClass(ExtPersonCredentialReplica.class);
            verify(credentialRepository).save(captor.capture());
            ExtPersonCredentialReplica saved = captor.getValue();
            assertThat(saved.getCredentialId()).isEqualTo(ID);
            assertThat(saved.getPersonId()).isEqualTo(OTHER_ID);
            assertThat(saved.getSkillCode()).isEqualTo("BRAKES-LIGHT");
            assertThat(saved.getCompetenceCode()).isEqualTo("BRAKES");
            assertThat(saved.getExpiresOn()).isEqualTo(java.time.LocalDate.parse("2029-01-02"));
            assertThat(saved.getStatus()).isEqualTo("ACTIVE");
            assertThat(saved.getAggregateVersion()).isEqualTo(3);
            ProcessedEvent mark = capturedProcessedEvent();
            assertThat(mark.getEventId()).isEqualTo("evt-9");
            assertThat(mark.getOwner()).isEqualTo(PeopleReplicaEventsListener.OWNER_PEOPLE);
        }

        @Test
        @DisplayName("#2122 credential: skips a strictly stale version, applies an equal one, still writes the mark")
        void credentialUpdatedStaleGuard() {
            when(credentialRepository.findById(ID))
                    .thenReturn(Optional.of(ExtPersonCredentialReplica.builder()
                            .credentialId(ID)
                            .personId(OTHER_ID)
                            .status("ACTIVE")
                            .aggregateVersion(5)
                            .build()));

            peopleListener.onPeopleEvent(
                    envelope("evt-10", PersonCredentialUpdatedV1.EVENT_TYPE, credentialPayload("REVOKED")));

            verify(credentialRepository, never()).save(any());
            assertThat(capturedProcessedEvent().getEventId()).isEqualTo("evt-10");

            peopleListener.onPeopleEvent(
                    envelope("evt-11", PersonCredentialUpdatedV1.EVENT_TYPE, 5, credentialPayload("REVOKED")));
            ArgumentCaptor<ExtPersonCredentialReplica> captor =
                    ArgumentCaptor.forClass(ExtPersonCredentialReplica.class);
            verify(credentialRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo("REVOKED");
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
                    {"eventId":"evt-5","eventType":"people.skill.updated","payload":{}}""");

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
        @DisplayName("rethrows a lost-connection database error but swallows and records a malformed payload")
        void lostConnectionVersusMalformed() {
            doThrow(new DataAccessResourceFailureException("connection reset"))
                    .when(personRepository)
                    .findById(any());

            assertThatThrownBy(() -> peopleListener.onPeopleContactEvent(
                            envelope("evt-1", PersonUpdatedV1.EVENT_TYPE, personPayload())))
                    .isInstanceOf(DataAccessResourceFailureException.class);
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
                     "status":"ACTIVE"}""".formatted(ID, SITE_ID)));

            ArgumentCaptor<ExtBayReplica> captor = ArgumentCaptor.forClass(ExtBayReplica.class);
            verify(bayRepository).save(captor.capture());
            assertThat(captor.getValue().getBayId()).isEqualTo(ID);
            assertThat(captor.getValue().getLocationId()).isEqualTo(SITE_ID);
            assertThat(captor.getValue().getName()).isEqualTo("Front Bay 1");
            assertThat(captor.getValue().isActive()).isTrue();
            assertThat(captor.getValue().getAggregateVersion()).isEqualTo(3);

            // #2264: pos-location no longer emits this fact, but a stray or replayed one is handled
            // safely — the row, if still present, is marked inactive rather than removed.
            when(bayRepository.findById(ID)).thenReturn(Optional.of(captor.getValue()));
            locationListener.onLocationEvent(envelope("evt-2", BayDeletedV1.EVENT_TYPE, """
                    {"bayId":"%s"}""".formatted(ID)));
            verify(bayRepository, never()).deleteById(any());
            ArgumentCaptor<ExtBayReplica> secondSave = ArgumentCaptor.forClass(ExtBayReplica.class);
            verify(bayRepository, org.mockito.Mockito.times(2)).save(secondSave.capture());
            assertThat(secondSave.getAllValues().get(1).isActive()).isFalse();
        }

        @Test
        @DisplayName("#1656 mobile unit: maps identity and base site, and honours the stale-version guard")
        void mobileUnitMapping() {
            String payload = """
                    {"mobileUnitId":"%s","baseLocationId":"%s","name":"Van 3","status":"ACTIVE"}""".formatted(ID, SITE_ID);
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
        @DisplayName("PR #2278 HIGH: a stray bay delete older than the held version is ignored")
        void staleBayDeleteIsIgnored() {
            when(bayRepository.findById(ID))
                    .thenReturn(Optional.of(ExtBayReplica.builder()
                            .bayId(ID)
                            .locationId(SITE_ID)
                            .active(true)
                            .aggregateVersion(9)
                            .build()));

            locationListener.onLocationEvent(envelope("evt-1", BayDeletedV1.EVENT_TYPE, 5, """
                    {"bayId":"%s"}""".formatted(ID)));

            verify(bayRepository, never()).save(any());
        }

        @Test
        @DisplayName("PR #2278 HIGH: a bay delete newer than the held version marks it inactive and stores the version")
        void newerBayDeleteMarksInactiveAndStoresVersion() {
            when(bayRepository.findById(ID))
                    .thenReturn(Optional.of(ExtBayReplica.builder()
                            .bayId(ID)
                            .locationId(SITE_ID)
                            .active(true)
                            .aggregateVersion(5)
                            .build()));

            locationListener.onLocationEvent(envelope("evt-1", BayDeletedV1.EVENT_TYPE, 9, """
                    {"bayId":"%s"}""".formatted(ID)));

            ArgumentCaptor<ExtBayReplica> captor = ArgumentCaptor.forClass(ExtBayReplica.class);
            verify(bayRepository).save(captor.capture());
            assertThat(captor.getValue().isActive()).isFalse();
            assertThat(captor.getValue().getAggregateVersion()).isEqualTo(9);
        }

        @Test
        @DisplayName("PR #2278 HIGH: a stray mobile-unit delete older than the held version is ignored")
        void staleMobileUnitDeleteIsIgnored() {
            when(mobileUnitRepository.findById(ID))
                    .thenReturn(Optional.of(ExtMobileUnitReplica.builder()
                            .mobileUnitId(ID)
                            .baseLocationId(SITE_ID)
                            .active(true)
                            .aggregateVersion(9)
                            .build()));

            locationListener.onLocationEvent(envelope("evt-1", MobileUnitDeletedV1.EVENT_TYPE, 5, """
                    {"mobileUnitId":"%s"}""".formatted(ID)));

            verify(mobileUnitRepository, never()).save(any());
        }

        @Test
        @DisplayName(
                "PR #2278 HIGH: a mobile-unit delete newer than the held version marks it inactive and stores the version")
        void newerMobileUnitDeleteMarksInactiveAndStoresVersion() {
            when(mobileUnitRepository.findById(ID))
                    .thenReturn(Optional.of(ExtMobileUnitReplica.builder()
                            .mobileUnitId(ID)
                            .baseLocationId(SITE_ID)
                            .active(true)
                            .aggregateVersion(5)
                            .build()));

            locationListener.onLocationEvent(envelope("evt-1", MobileUnitDeletedV1.EVENT_TYPE, 9, """
                    {"mobileUnitId":"%s"}""".formatted(ID)));

            ArgumentCaptor<ExtMobileUnitReplica> captor = ArgumentCaptor.forClass(ExtMobileUnitReplica.class);
            verify(mobileUnitRepository).save(captor.capture());
            assertThat(captor.getValue().isActive()).isFalse();
            assertThat(captor.getValue().getAggregateVersion()).isEqualTo(9);
        }

        @Test
        @DisplayName("#1656: a bay status the consumer has never seen before is not active")
        void unknownBayStatusIsNotActive() {
            // The owner publishes status, never a boolean active flag, and its vocabulary is not this
            // module's to fix. Allow-listing ACTIVE means a value nobody here has seen keeps the unit
            // off the dispatch board instead of advertising a bay that may be out of service.
            locationListener.onLocationEvent(envelope("evt-1", BayUpdatedV1.EVENT_TYPE, """
                    {"bayId":"%s","locationId":"%s","name":"Front Bay 1","bayType":"GENERAL",
                     "status":"AWAITING_INSPECTION"}""".formatted(ID, SITE_ID)));

            ArgumentCaptor<ExtBayReplica> captor = ArgumentCaptor.forClass(ExtBayReplica.class);
            verify(bayRepository).save(captor.capture());
            assertThat(captor.getValue().isActive()).isFalse();
            // The row is still replicated — only its activeness is withheld.
            assertThat(captor.getValue().getName()).isEqualTo("Front Bay 1");
        }

        @Test
        @DisplayName("#1656: an unknown or absent mobile-unit status is not active; ACTIVE binds in any casing")
        void mobileUnitStatusAllowList() {
            // MobileUnitEntity.status is a free-text column upstream, so a deny-list of the values
            // known today would let a typo put an undispatchable van on the board as available.
            locationListener.onLocationEvent(
                    envelope("evt-1", MobileUnitUpdatedV1.EVENT_TYPE, """
                    {"mobileUnitId":"%s","baseLocationId":"%s","name":"Van 3","status":"IN_TRANSIT"}""".formatted(ID, SITE_ID)));
            locationListener.onLocationEvent(
                    envelope("evt-2", MobileUnitUpdatedV1.EVENT_TYPE, """
                    {"mobileUnitId":"%s","baseLocationId":"%s","name":"Van 4"}""".formatted(OTHER_ID, SITE_ID)));
            locationListener.onLocationEvent(
                    envelope("evt-3", MobileUnitUpdatedV1.EVENT_TYPE, """
                    {"mobileUnitId":"%s","baseLocationId":"%s","name":"Van 5","status":"active"}""".formatted(THIRD_ID, SITE_ID)));

            ArgumentCaptor<ExtMobileUnitReplica> captor = ArgumentCaptor.forClass(ExtMobileUnitReplica.class);
            verify(mobileUnitRepository, org.mockito.Mockito.times(3)).save(captor.capture());
            assertThat(captor.getAllValues())
                    .extracting(ExtMobileUnitReplica::isActive)
                    .containsExactly(false, false, true);
        }

        @Test
        @DisplayName("#2267: a fact carrying maxDutyClass replicates it onto ext_mobile_unit")
        void mobileUnitMaxDutyClassIsReplicated() {
            locationListener.onLocationEvent(
                    envelope("evt-1", MobileUnitUpdatedV1.EVENT_TYPE, """
                    {"mobileUnitId":"%s","baseLocationId":"%s","name":"Van 3","status":"ACTIVE",
                     "maxDutyClass":5}""".formatted(ID, SITE_ID)));

            ArgumentCaptor<ExtMobileUnitReplica> captor = ArgumentCaptor.forClass(ExtMobileUnitReplica.class);
            verify(mobileUnitRepository).save(captor.capture());
            assertThat(captor.getValue().getMaxDutyClass()).isEqualTo(5);
        }

        @Test
        @DisplayName("#2267: a fact without the maxDutyClass field keeps the ceiling already replicated")
        void mobileUnitMaxDutyClassAbsentKeepsExisting() {
            when(mobileUnitRepository.findById(ID))
                    .thenReturn(Optional.of(ExtMobileUnitReplica.builder()
                            .mobileUnitId(ID)
                            .maxDutyClass(5)
                            .aggregateVersion(1)
                            .build()));

            locationListener.onLocationEvent(
                    envelope("evt-1", MobileUnitUpdatedV1.EVENT_TYPE, """
                    {"mobileUnitId":"%s","baseLocationId":"%s","name":"Van 3","status":"ACTIVE"}""".formatted(ID, SITE_ID)));

            ArgumentCaptor<ExtMobileUnitReplica> captor = ArgumentCaptor.forClass(ExtMobileUnitReplica.class);
            verify(mobileUnitRepository).save(captor.capture());
            assertThat(captor.getValue().getMaxDutyClass()).isEqualTo(5);
        }

        @Test
        @DisplayName("#1656: an unknown location-domain fact is ignored but still recorded for the manifest")
        void unknownFactTypeIsRecordedNotApplied() {
            // pos-location publishes storage-location facts this module ignores. An ignored fact may
            // neither break the consumer nor skew the manifest window.
            locationListener.onLocationEvent(envelope("evt-1", "location.storage-location.updated", "{}"));

            verify(bayRepository, never()).save(any());
            verify(mobileUnitRepository, never()).save(any());
            assertThat(capturedProcessedEvent().getOwner()).isEqualTo("location");
        }

        @Test
        @DisplayName("#1668: a plausible-but-wrong bay payload shape is rejected loudly, not written half-populated")
        void wrongBayPayloadShapeIsRejectedNotHalfWritten() {
            // pos-location owns BayUpdatedV1 and publishes it as of issue #1668. This locks the
            // field names against a future producer change: both ways the shape can drift have to
            // fail loudly, or the board goes quietly empty and nothing anywhere says why.

            // (a) the identifier under another name: the compact constructor throws, Jackson reports
            // a DatabindException, nothing is written.
            locationListener.onLocationEvent(envelope("evt-1", BayUpdatedV1.EVENT_TYPE, """
                    {"id":"%s","locationId":"%s","name":"Front Bay 1","status":"ACTIVE"}""".formatted(ID, SITE_ID)));

            // (b) the site scope under another name. This is the silent one: the record binds, the
            // row saves with location_id null, and the roster query — which scopes by location_id —
            // can never return it. An empty panel, no exception, no log, indistinguishable from the
            // producer simply not having shipped yet.
            locationListener.onLocationEvent(envelope("evt-2", BayUpdatedV1.EVENT_TYPE, """
                    {"bayId":"%s","siteId":"%s","name":"Front Bay 1","status":"ACTIVE"}""".formatted(ID, SITE_ID)));

            verify(bayRepository, never()).save(any());
            assertThat(meterRegistry
                            .get("replica.payload.rejected")
                            .tag("owner", "location")
                            .counter()
                            .count())
                    .as("both shape mismatches are counted, so a wrong guess is visible")
                    .isEqualTo(2.0);
            // Still recorded for the manifest: a rejected fact is a processed fact, or the owner's
            // window reads as permanent drift and triggers useless replays.
            verify(processedEventRepository, org.mockito.Mockito.times(2)).save(any());
        }

        @Test
        @DisplayName("#1668: a mobile-unit payload with no base site is rejected rather than made invisible")
        void mobileUnitWithoutBaseLocationIsRejected() {
            locationListener.onLocationEvent(
                    envelope("evt-1", MobileUnitUpdatedV1.EVENT_TYPE, """
                    {"mobileUnitId":"%s","siteId":"%s","name":"Van 3","status":"ACTIVE"}""".formatted(ID, SITE_ID)));

            verify(mobileUnitRepository, never()).save(any());
            assertThat(meterRegistry
                            .get("replica.payload.rejected")
                            .tag("owner", "location")
                            .counter()
                            .count())
                    .isEqualTo(1.0);
        }

        // -- #2261 DECISION-LOCATION-025: bay specialty map replica -----------------------------

        private String baySpecialtyMapUpdated(String eventId, long version) {
            return """
                    {"eventId":"%s","eventType":"%s","aggregateVersion":%d,
                     "payload":{"tenantId":"%s","aggregateVersion":%d,
                       "entries":[
                         {"bayType":"ALIGNMENT","operationCodes":["ALIGN-4-WHEEL"],"acceptsGeneralWork":false},
                         {"bayType":"GENERAL_SERVICE","operationCodes":[],"acceptsGeneralWork":true},
                         {"bayType":"WASH_DETAIL","operationCodes":[],"acceptsGeneralWork":false}
                       ]}}
                    """.formatted(eventId, BaySpecialtyMapUpdatedV1.EVENT_TYPE, version, ID, version);
        }

        @Test
        @DisplayName("#2261: applies a full replace of the tenant's bay specialty map")
        void baySpecialtyMapAppliesFullReplace() {
            when(bayTypeRepository.findFirstByOrderByBayTypeAsc()).thenReturn(java.util.Optional.empty());

            locationListener.onLocationEvent(baySpecialtyMapUpdated("evt-map-1", 1));

            verify(baySpecialtyMapRepository).deleteAll();
            verify(bayTypeRepository).deleteAll();

            ArgumentCaptor<ExtBayTypeReplica> bayTypeCaptor = ArgumentCaptor.forClass(ExtBayTypeReplica.class);
            verify(bayTypeRepository, org.mockito.Mockito.times(3)).save(bayTypeCaptor.capture());
            assertThat(bayTypeCaptor.getAllValues())
                    .extracting(ExtBayTypeReplica::getBayType, ExtBayTypeReplica::isAcceptsGeneralWork)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple("ALIGNMENT", false),
                            org.assertj.core.groups.Tuple.tuple("GENERAL_SERVICE", true),
                            org.assertj.core.groups.Tuple.tuple("WASH_DETAIL", false));
            assertThat(bayTypeCaptor.getAllValues())
                    .allSatisfy(row -> assertThat(row.getAggregateVersion()).isEqualTo(1L));

            ArgumentCaptor<ExtBaySpecialtyMapReplica> opCaptor =
                    ArgumentCaptor.forClass(ExtBaySpecialtyMapReplica.class);
            verify(baySpecialtyMapRepository).save(opCaptor.capture());
            assertThat(opCaptor.getValue().getBayType()).isEqualTo("ALIGNMENT");
            assertThat(opCaptor.getValue().getOperationCode()).isEqualTo("ALIGN-4-WHEEL");

            verify(processedEventRepository).save(any());
        }

        @Test
        @DisplayName("#2261: ignores a strictly older bay specialty map version but re-applies an equal one")
        void baySpecialtyMapStaleGuardIsStrictlyBelow() {
            when(bayTypeRepository.findFirstByOrderByBayTypeAsc())
                    .thenReturn(java.util.Optional.of(ExtBayTypeReplica.builder()
                            .bayType("ALIGNMENT")
                            .acceptsGeneralWork(false)
                            .aggregateVersion(7)
                            .build()));

            locationListener.onLocationEvent(baySpecialtyMapUpdated("evt-map-old", 6));
            verify(baySpecialtyMapRepository, never()).deleteAll();
            verify(bayTypeRepository, never()).deleteAll();
            // The stale fact is still recorded so the owner's manifest reconciles.
            verify(processedEventRepository).save(any());

            locationListener.onLocationEvent(baySpecialtyMapUpdated("evt-map-equal", 7));
            verify(baySpecialtyMapRepository).deleteAll();
            verify(bayTypeRepository).deleteAll();
        }

        @Test
        @DisplayName("#2261: is idempotent on a replayed bay specialty map eventId")
        void baySpecialtyMapReplayIsNoOp() {
            when(processedEventRepository.existsById("evt-map-1")).thenReturn(true);

            locationListener.onLocationEvent(baySpecialtyMapUpdated("evt-map-1", 1));

            verify(baySpecialtyMapRepository, never()).deleteAll();
            verify(bayTypeRepository, never()).deleteAll();
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("#2261: acceptsGeneralWork maps true/false from BayUpdatedV1")
        void bayAcceptsGeneralWorkIsMapped() {
            locationListener.onLocationEvent(
                    envelope("evt-agw-1", BayUpdatedV1.EVENT_TYPE, """
                    {"bayId":"%s","locationId":"%s","name":"Wash Bay","status":"ACTIVE",
                     "acceptsGeneralWork":false}""".formatted(ID, SITE_ID)));

            ArgumentCaptor<ExtBayReplica> captor = ArgumentCaptor.forClass(ExtBayReplica.class);
            verify(bayRepository).save(captor.capture());
            assertThat(captor.getValue().isAcceptsGeneralWork()).isFalse();
        }

        @Test
        @DisplayName("#2261: acceptsGeneralWork absent from the fact keeps the already-replicated value")
        void bayAcceptsGeneralWorkAbsentKeepsExistingValue() {
            when(bayRepository.findById(any()))
                    .thenReturn(java.util.Optional.of(ExtBayReplica.builder()
                            .bayId(ID)
                            .locationId(SITE_ID)
                            .acceptsGeneralWork(false)
                            .aggregateVersion(1)
                            .build()));

            locationListener.onLocationEvent(
                    envelope("evt-agw-2", BayUpdatedV1.EVENT_TYPE, """
                    {"bayId":"%s","locationId":"%s","name":"Wash Bay","status":"ACTIVE"}""".formatted(ID, SITE_ID)));

            ArgumentCaptor<ExtBayReplica> captor = ArgumentCaptor.forClass(ExtBayReplica.class);
            verify(bayRepository).save(captor.capture());
            // A pre-DECISION-LOCATION-025 producer's shape: no acceptsGeneralWork key at all, so the
            // already-replicated false is kept rather than defaulting back to true.
            assertThat(captor.getValue().isAcceptsGeneralWork()).isFalse();
        }

        @Test
        @DisplayName("#2261: acceptsGeneralWork absent on a brand-new bay row defaults to true")
        void bayAcceptsGeneralWorkAbsentOnNewRowDefaultsTrue() {
            locationListener.onLocationEvent(
                    envelope("evt-agw-3", BayUpdatedV1.EVENT_TYPE, """
                    {"bayId":"%s","locationId":"%s","name":"General Bay","status":"ACTIVE"}""".formatted(ID, SITE_ID)));

            ArgumentCaptor<ExtBayReplica> captor = ArgumentCaptor.forClass(ExtBayReplica.class);
            verify(bayRepository).save(captor.capture());
            assertThat(captor.getValue().isAcceptsGeneralWork()).isTrue();
        }
    }

    @Nested
    @DisplayName("manifest listeners")
    class Manifests {

        /** A replay request the broker acknowledges; a test that wants a failure re-stubs it. */
        @BeforeEach
        void brokerAcknowledgesReplayRequests() {
            when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        }

        private String manifestMessage(long eventCount, String checksum) {
            return """
                    {"eventId":"evt-1","eventType":"x.reconciliation.manifest",
                     "payload":{"tenantId":"%s","windowStartUtc":"%s","windowEndUtc":"%s","eventCount":%d,
                       "eventIdsChecksum":"%s","eventTypeCounts":null}}
                    """.formatted(TENANT_A, WINDOW_START, WINDOW_END, eventCount, checksum);
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
                            TENANT_A,
                            UuidV7Timestamps.minStringAt(WINDOW_START),
                            UuidV7Timestamps.minStringAt(WINDOW_END)))
                    .thenReturn(ids);

            listener.onManifest(manifestMessage(1, ReconciliationManifestV1.checksumOf(ids)));
            verify(kafkaTemplate, never()).send(any(ProducerRecord.class));

            listener.onManifest(manifestMessage(2, "owner-checksum"));
            assertThat(driftCount("customer")).isEqualTo(1.0);
            verify(kafkaTemplate).send(replayOn("customer.commands.v1"));
        }

        @Test
        @DisplayName("location manifest: drops an unparseable manifest, propagates a failed replay publish")
        void locationManifestRobustness() {
            LocationManifestListener listener = new LocationManifestListener(
                    processedEventRepository, kafkaTemplate, objectMapper, meterRegistryProvider);
            ReflectionTestUtils.setField(listener, "locationCommandsTopic", "location.commands.v1");

            listener.onManifest("{not json");
            verify(kafkaTemplate, never()).send(any(ProducerRecord.class));

            when(processedEventRepository.findEventIdsInRange(anyString(), any(), anyString(), anyString()))
                    .thenReturn(List.of());
            when(kafkaTemplate.send(any(ProducerRecord.class))).thenThrow(new IllegalStateException("broker down"));

            assertThatThrownBy(() -> listener.onManifest(manifestMessage(3, "owner-checksum")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("broker down");

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
                            TENANT_A,
                            UuidV7Timestamps.minStringAt(WINDOW_START),
                            UuidV7Timestamps.minStringAt(WINDOW_END)))
                    .thenReturn(ids);

            listener.onManifest(manifestMessage(1, ReconciliationManifestV1.checksumOf(ids)));
            verify(kafkaTemplate, never()).send(any(ProducerRecord.class));

            listener.onManifest(manifestMessage(2, "owner-checksum"));
            assertThat(driftCount("people")).isEqualTo(1.0);
            verify(kafkaTemplate).send(replayOn("people.commands.v1"));
        }

        @Test
        @DisplayName("customer and location manifests: a manifest without tenantId is skipped, not read as any"
                + " tenant's (WS4-3)")
        void manifestWithoutTenantIsSkipped() {
            CustomerManifestListener customer = new CustomerManifestListener(
                    processedEventRepository, kafkaTemplate, objectMapper, meterRegistryProvider);
            ReflectionTestUtils.setField(customer, "customerCommandsTopic", "customer.commands.v1");
            LocationManifestListener location = new LocationManifestListener(
                    processedEventRepository, kafkaTemplate, objectMapper, meterRegistryProvider);
            ReflectionTestUtils.setField(location, "locationCommandsTopic", "location.commands.v1");
            // Published before manifests were per tenant (ADR-0062 WS4-3): it summarised every
            // tenant's rows at once, so no single tenant's ledger can be compared against it.
            String legacy = """
                    {"eventType":"x.reconciliation.manifest",
                     "payload":{"windowStartUtc":"%s","windowEndUtc":"%s","eventCount":2,
                       "eventIdsChecksum":"owner-checksum","eventTypeCounts":null}}
                    """.formatted(WINDOW_START, WINDOW_END);

            customer.onManifest(legacy);
            location.onManifest(legacy);

            verify(processedEventRepository, never()).findEventIdsInRange(any(), any(), any(), any());
            verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
            assertThat(meterRegistry.find("replica.drift").counters()).isEmpty();
            assertThat(meterRegistry.find("replica.manifest.skipped").counters())
                    .extracting(c -> c.getId().getTag("owner"))
                    .containsExactlyInAnyOrder("customer", "location");
        }

        private Consumer<String> customerManifests() {
            CustomerManifestListener listener = new CustomerManifestListener(
                    processedEventRepository, kafkaTemplate, objectMapper, meterRegistryProvider);
            ReflectionTestUtils.setField(listener, "customerCommandsTopic", "customer.commands.v1");
            return listener::onManifest;
        }

        private Consumer<String> locationManifests() {
            LocationManifestListener listener = new LocationManifestListener(
                    processedEventRepository, kafkaTemplate, objectMapper, meterRegistryProvider);
            ReflectionTestUtils.setField(listener, "locationCommandsTopic", "location.commands.v1");
            return listener::onManifest;
        }

        private Consumer<String> inventoryManifests() {
            InventoryManifestListener listener = new InventoryManifestListener(
                    processedEventRepository, kafkaTemplate, objectMapper, meterRegistryProvider);
            ReflectionTestUtils.setField(listener, "inventoryCommandsTopic", "inventory.commands.v1");
            return listener::onManifest;
        }

        private Consumer<String> invoiceManifests() {
            InvoiceManifestListener listener = new InvoiceManifestListener(
                    processedEventRepository, kafkaTemplate, objectMapper, meterRegistryProvider);
            ReflectionTestUtils.setField(listener, "invoiceCommandsTopic", "invoice.commands.v1");
            return listener::onManifest;
        }

        private Consumer<String> peopleManifests() {
            PeopleManifestListener listener = new PeopleManifestListener(
                    processedEventRepository, kafkaTemplate, objectMapper, meterRegistryProvider);
            ReflectionTestUtils.setField(listener, "peopleCommandsTopic", "people.commands.v1");
            return listener::onManifest;
        }

        /** Every manifest listener of the module, keyed by the {@code owner} tag of its drift metric. */
        private Map<String, Consumer<String>> manifestListeners() {
            Map<String, Consumer<String>> listeners = new LinkedHashMap<>();
            listeners.put("customer", customerManifests());
            listeners.put("location", locationManifests());
            listeners.put("inventory", inventoryManifests());
            listeners.put("invoice", invoiceManifests());
            listeners.put("people", peopleManifests());
            return listeners;
        }

        /**
         * The ledger read is a manifest listener's only data access and sits outside both of its
         * catch blocks, so a lock or query timeout there must leave {@code onManifest} for the
         * container's error handler (retry with backoff, then {@code {topic}.dlq}, ADR-0044 §4)
         * instead of being logged and committed as consumed. Nothing is counted or sent before
         * the read, so the redelivery starts from scratch.
         */
        private void assertTransientLedgerFailurePropagates(Consumer<String> onManifest) {
            when(processedEventRepository.findEventIdsInRange(anyString(), any(), anyString(), anyString()))
                    .thenThrow(new QueryTimeoutException("lock wait"));

            assertThatThrownBy(() -> onManifest.accept(manifestMessage(2, "owner-checksum")))
                    .isInstanceOf(QueryTimeoutException.class);

            verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
            assertThat(meterRegistry.find("replica.drift").counters()).isEmpty();
        }

        @Test
        @DisplayName("#2354 customer manifest: a transient database error on the ledger read propagates so the"
                + " container retries")
        void customerManifestTransientFailurePropagates() {
            assertTransientLedgerFailurePropagates(customerManifests());
        }

        @Test
        @DisplayName("#2354 location manifest: a transient database error on the ledger read propagates so the"
                + " container retries")
        void locationManifestTransientFailurePropagates() {
            assertTransientLedgerFailurePropagates(locationManifests());
        }

        @Test
        @DisplayName("#2354 inventory manifest: a transient database error on the ledger read propagates so the"
                + " container retries")
        void inventoryManifestTransientFailurePropagates() {
            assertTransientLedgerFailurePropagates(inventoryManifests());
        }

        @Test
        @DisplayName("#2354 invoice manifest: a transient database error on the ledger read propagates so the"
                + " container retries")
        void invoiceManifestTransientFailurePropagates() {
            assertTransientLedgerFailurePropagates(invoiceManifests());
        }

        @Test
        @DisplayName("#2354 people manifest: a transient database error on the ledger read propagates so the"
                + " container retries")
        void peopleManifestTransientFailurePropagates() {
            assertTransientLedgerFailurePropagates(peopleManifests());
        }

        @Test
        @DisplayName("#2354 every manifest listener still swallows an unparseable manifest")
        void unparseableManifestStaysSwallowed() {
            manifestListeners().forEach((owner, onManifest) -> {
                assertThatCode(() -> onManifest.accept("{not json"))
                        .as("%s: unparseable manifest", owner)
                        .doesNotThrowAnyException();
                assertThat(driftCount(owner)).isZero();
            });
            verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        }

        /**
         * The owners publish consecutive, non-overlapping windows once each, so no later manifest
         * re-detects a window whose replay request was lost. A send that fails at once must
         * therefore leave {@code onManifest} for the container's error handler (retry with backoff,
         * then {@code {topic}.dlq}); the redelivery re-runs the comparison and repeats the request.
         */
        @Test
        @DisplayName("#2419 every manifest listener: a replay request that fails to send propagates so the"
                + " container redelivers the manifest")
        void failedReplaySendPropagates() {
            when(processedEventRepository.findEventIdsInRange(anyString(), any(), anyString(), anyString()))
                    .thenReturn(List.of());
            when(kafkaTemplate.send(any(ProducerRecord.class))).thenThrow(new IllegalStateException("broker down"));

            manifestListeners().forEach((owner, onManifest) -> {
                assertThatThrownBy(() -> onManifest.accept(manifestMessage(3, "owner-checksum")))
                        .as("%s: failed replay send", owner)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("broker down");
                assertThat(driftCount(owner)).isEqualTo(1.0);
            });
        }

        /**
         * {@code KafkaTemplate.send} is asynchronous: a broker-side failure (delivery timeout, not
         * leader, record too large) only completes the returned future exceptionally. The listener
         * waits on it, so that failure reaches the container too instead of passing unseen.
         */
        @Test
        @DisplayName("#2419 every manifest listener: a replay request the broker rejects propagates so the"
                + " container redelivers the manifest")
        void brokerRejectedReplayPropagates() {
            when(processedEventRepository.findEventIdsInRange(anyString(), any(), anyString(), anyString()))
                    .thenReturn(List.of());
            when(kafkaTemplate.send(any(ProducerRecord.class)))
                    .thenAnswer(invocation -> CompletableFuture.failedFuture(new KafkaException("not leader")));

            manifestListeners().forEach((owner, onManifest) -> {
                assertThatThrownBy(() -> onManifest.accept(manifestMessage(3, "owner-checksum")))
                        .as("%s: broker-rejected replay", owner)
                        .isInstanceOf(KafkaException.class)
                        .hasRootCauseMessage("not leader");
                assertThat(driftCount(owner)).isEqualTo(1.0);
            });
        }

        @Test
        @DisplayName("#2354 every manifest listener: a redelivered manifest re-runs the comparison and repeats the"
                + " same replay request for the same window")
        @SuppressWarnings("unchecked")
        void redeliveryRepeatsTheSameReplayRequest() {
            when(processedEventRepository.findEventIdsInRange(anyString(), any(), anyString(), anyString()))
                    .thenReturn(List.of());

            manifestListeners().forEach((owner, onManifest) -> {
                clearInvocations(kafkaTemplate);
                String manifest = manifestMessage(3, "owner-checksum");

                onManifest.accept(manifest);
                onManifest.accept(manifest);

                ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
                verify(kafkaTemplate, times(2)).send(sent.capture());
                ProducerRecord<String, String> first = sent.getAllValues().get(0);
                ProducerRecord<String, String> second = sent.getAllValues().get(1);
                assertThat(first.topic()).as(owner).isEqualTo(owner + ".commands.v1");
                assertThat(first.key()).as(owner).isEqualTo(WINDOW_START.toString());
                assertThat(second.topic()).as(owner).isEqualTo(first.topic());
                assertThat(second.key()).as(owner).isEqualTo(first.key());
                assertThat(second.value()).as(owner).isEqualTo(first.value());
            });
        }
    }

    /** The one replay command handed to Kafka: it must ride the manifest's tenant header (ADR-0062 §3). */
    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> capturedReplay() {
        ArgumentCaptor<ProducerRecord<String, String>> record = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(record.capture());
        assertThat(TenantKafkaHeaders.read(record.getValue().headers())).contains(TENANT_A);
        return record.getValue();
    }

    /** Matches a replay command routed to {@code topic} under the manifest's tenant header. */
    private static ProducerRecord<String, String> replayOn(String topic) {
        return org.mockito.ArgumentMatchers.argThat(
                (ProducerRecord<String, String> record) -> topic.equals(record.topic())
                        && TenantKafkaHeaders.read(record.headers())
                                .filter(TENANT_A::equals)
                                .isPresent());
    }
}
