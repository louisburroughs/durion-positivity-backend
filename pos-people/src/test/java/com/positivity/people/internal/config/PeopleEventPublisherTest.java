package com.positivity.people.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.people.EmployeeUpdatedV1;
import com.positivity.domainevents.people.StaffingAssignmentUpdatedV1;
import com.positivity.domainevents.peoplecontact.PersonUpsertRequestedV1;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.enums.AssignmentStatus;
import com.positivity.people.internal.enums.EmployeeStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link PeopleEventPublisher} (ADR-0044 §2).
 *
 * <p>
 * This class emits two different shapes down two different paths, and mixing
 * them up would be silently wrong rather than loud:
 *
 * <ul>
 * <li><b>Facts</b> ({@code people.employee.updated},
 * {@code people.staffing-assignment.updated}) go to this module's own events
 * topic as full {@link DomainEventEnvelope}s.</li>
 * <li><b>A person upsert is a command</b>, not a fact — it goes to
 * pos-people-contact's commands topic as the plain
 * {@code {commandType, eventId, payload}} message its listener expects, keyed on
 * the person id, with the eventId serving as the receiver's idempotency key. An
 * envelope here would simply not be understood by the receiver.</li>
 * </ul>
 *
 * <p>
 * Every method is a no-op when Kafka is disabled, since the outbox writer bean
 * is conditional and these are called inside business transactions that must
 * still commit.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PeopleEventPublisher — people facts and identity commands")
class PeopleEventPublisherTest {

    private static final UUID EMPLOYEE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID PERSON_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID ASSIGNMENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c4");
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");
    private static final String EVENTS_TOPIC = "people.events.v1";
    private static final String COMMANDS_TOPIC = "people-contact.commands.v1";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ObjectProvider<OutboxEventWriter> outboxEventWriter;

    @Mock
    private OutboxEventWriter writer;

    private PeopleEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new PeopleEventPublisher(
                Clock.fixed(NOW, ZoneOffset.UTC), objectMapper, outboxEventWriter, EVENTS_TOPIC, COMMANDS_TOPIC);
        when(outboxEventWriter.getIfAvailable()).thenReturn(writer);
    }

    private static Employee employee(EmployeeStatus status) {
        Employee employee = new Employee();
        employee.setId(EMPLOYEE_ID);
        employee.setPersonId(PERSON_ID);
        employee.setEmployeeNumber("E-1001");
        employee.setStatus(status);
        employee.setHireDate(LocalDate.of(2026, 1, 15));
        employee.setStatusEffectiveAt(NOW.minusSeconds(3_600));
        return employee;
    }

    private static EmployeeLocationAssignment assignment(AssignmentStatus status) {
        EmployeeLocationAssignment assignment = new EmployeeLocationAssignment();
        assignment.setId(ASSIGNMENT_ID);
        assignment.setEmployee(employee(EmployeeStatus.ACTIVE));
        assignment.setLocationId(LOCATION_ID);
        assignment.setRole("TECHNICIAN");
        assignment.setPrimary(true);
        assignment.setStatus(status);
        assignment.setEffectiveFrom(LocalDate.of(2026, 2, 1));
        return assignment;
    }

    private DomainEventEnvelope<?> captureEnvelope() {
        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.captor();
        verify(writer).publish(org.mockito.ArgumentMatchers.eq(EVENTS_TOPIC), captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("facts")
    class Facts {

        @Test
        @DisplayName("publishes an employee fact keyed on the employee")
        void employeeFact() {
            publisher.publishEmployeeUpdated(employee(EmployeeStatus.ACTIVE));

            DomainEventEnvelope<?> envelope = captureEnvelope();
            assertThat(envelope.eventType()).isEqualTo(EmployeeUpdatedV1.EVENT_TYPE);
            assertThat(envelope.aggregateId()).isEqualTo(EMPLOYEE_ID);
            assertThat(envelope.sourceService()).isEqualTo("pos-people");
            // Employee has no optimistic-lock version, so the emission timestamp orders the stream.
            assertThat(envelope.aggregateVersion()).isEqualTo(NOW.toEpochMilli());

            EmployeeUpdatedV1 payload = (EmployeeUpdatedV1) envelope.payload();
            assertThat(payload.employeeId()).isEqualTo(EMPLOYEE_ID);
            assertThat(payload.personId()).isEqualTo(PERSON_ID);
            assertThat(payload.employeeNumber()).isEqualTo("E-1001");
            assertThat(payload.status()).isEqualTo("ACTIVE");
            assertThat(payload.hireDate()).isEqualTo(LocalDate.of(2026, 1, 15));
            assertThat(payload.terminationDate()).isNull();
        }

        @Test
        @DisplayName("defaults a null employee status to ACTIVE rather than emitting null")
        void nullEmployeeStatusDefaults() {
            publisher.publishEmployeeUpdated(employee(null));

            // The field is @NonNull on the event contract; emitting null would fail the consumer.
            assertThat(((EmployeeUpdatedV1) captureEnvelope().payload()).status())
                    .isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("publishes a staffing fact keyed on the assignment, carrying both ids")
        void staffingFact() {
            publisher.publishStaffingAssignmentUpdated(assignment(AssignmentStatus.ACTIVE));

            DomainEventEnvelope<?> envelope = captureEnvelope();
            assertThat(envelope.eventType()).isEqualTo(StaffingAssignmentUpdatedV1.EVENT_TYPE);
            // Keyed on the assignment, not the employee: one employee can hold several.
            assertThat(envelope.aggregateId()).isEqualTo(ASSIGNMENT_ID);

            StaffingAssignmentUpdatedV1 payload = (StaffingAssignmentUpdatedV1) envelope.payload();
            assertThat(payload.assignmentId()).isEqualTo(ASSIGNMENT_ID);
            assertThat(payload.employeeId()).isEqualTo(EMPLOYEE_ID);
            // personId is denormalized onto the fact so consumers need no employee lookup.
            assertThat(payload.personId()).isEqualTo(PERSON_ID);
            assertThat(payload.locationId()).isEqualTo(LOCATION_ID);
            assertThat(payload.role()).isEqualTo("TECHNICIAN");
            assertThat(payload.primary()).isTrue();
            assertThat(payload.status()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("defaults a null assignment status to ACTIVE")
        void nullAssignmentStatusDefaults() {
            publisher.publishStaffingAssignmentUpdated(assignment(null));

            assertThat(((StaffingAssignmentUpdatedV1) captureEnvelope().payload()).status())
                    .isEqualTo("ACTIVE");
        }
    }

    @Nested
    @DisplayName("identity command")
    class IdentityCommand {

        private PersonUpsertRequestedV1 command() {
            return new PersonUpsertRequestedV1(
                    PERSON_ID, "Ada", "Lovelace", "Ada", "ada@example.com", null, List.of("555-0001"), List.of());
        }

        @Test
        @DisplayName("sends a plain command message, not a fact envelope, keyed on the person")
        void commandShapeAndKey() {
            publisher.requestPersonUpsert(command());

            ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
            verify(writer)
                    .publishRaw(
                            org.mockito.ArgumentMatchers.eq(COMMANDS_TOPIC),
                            org.mockito.ArgumentMatchers.eq(PERSON_ID.toString()),
                            message.capture());
            // The receiver's listener reads {commandType, eventId, payload}; a DomainEventEnvelope
            // would not be understood.
            var parsed = objectMapper.readTree(message.getValue());
            assertThat(parsed.path("commandType").asString()).isEqualTo(PersonUpsertRequestedV1.COMMAND_TYPE);
            assertThat(parsed.path("eventId").asString()).isNotBlank();
            assertThat(parsed.path("payload").path("personId").asString()).isEqualTo(PERSON_ID.toString());
            assertThat(parsed.path("payload").path("firstName").asString()).isEqualTo("Ada");
            // Facts and commands must not cross paths.
            verify(writer, never()).publish(anyString(), any());
        }

        @Test
        @DisplayName("mints a fresh idempotency key per command so the receiver can de-duplicate")
        void freshEventIdPerCommand() {
            publisher.requestPersonUpsert(command());
            publisher.requestPersonUpsert(command());

            ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
            verify(writer, org.mockito.Mockito.times(2)).publishRaw(anyString(), anyString(), message.capture());
            String first = objectMapper
                    .readTree(message.getAllValues().get(0))
                    .path("eventId")
                    .asString();
            String second = objectMapper
                    .readTree(message.getAllValues().get(1))
                    .path("eventId")
                    .asString();
            assertThat(first).isNotEqualTo(second);
        }

        @Test
        @DisplayName("fails the transaction rather than queueing a command it could not serialize")
        void serializationFailureIsFatal() {
            ObjectMapper failing = org.mockito.Mockito.mock(ObjectMapper.class);
            when(failing.writeValueAsString(any())).thenThrow(new IllegalStateException("boom"));
            PeopleEventPublisher failingPublisher = new PeopleEventPublisher(
                    Clock.fixed(NOW, ZoneOffset.UTC), failing, outboxEventWriter, EVENTS_TOPIC, COMMANDS_TOPIC);

            assertThatThrownBy(() -> failingPublisher.requestPersonUpsert(command()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(PERSON_ID.toString());

            verify(writer, never()).publishRaw(anyString(), anyString(), anyString());
        }
    }

    @Test
    @DisplayName("every publish is a quiet no-op while Kafka is disabled")
    void kafkaDisabledIsANoOp() {
        when(outboxEventWriter.getIfAvailable()).thenReturn(null);

        publisher.publishEmployeeUpdated(employee(EmployeeStatus.ACTIVE));
        publisher.publishStaffingAssignmentUpdated(assignment(AssignmentStatus.ACTIVE));
        publisher.requestPersonUpsert(
                new PersonUpsertRequestedV1(PERSON_ID, "Ada", "Lovelace", null, null, null, List.of(), List.of()));

        // These run inside business transactions that must still commit without the outbox bean.
        verifyNoInteractions(writer);
    }
}
