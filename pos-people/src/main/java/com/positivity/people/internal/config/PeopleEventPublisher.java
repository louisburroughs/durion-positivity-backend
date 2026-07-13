package com.positivity.people.internal.config;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.people.EmployeeUpdatedV1;
import com.positivity.domainevents.people.StaffingAssignmentUpdatedV1;
import com.positivity.domainevents.peoplecontact.PersonUpsertRequestedV1;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Emits people HR facts and identity-upsert commands to the outbox after mutations
 * (ADR-0044 §2/§6, #875).
 *
 * <p>Facts ({@code people.employee.updated}, {@code people.staffing-assignment.updated}) go to
 * {@code people.events.v1} as {@link DomainEventEnvelope}s. Identity writes are NOT performed
 * locally: they are sent as {@code people-contact.person.upsert-requested} commands on
 * {@code people-contact.commands.v1}; pos-people-contact confirms with a
 * {@code people-contact.person.updated} fact that feeds this module's replica.
 *
 * <p>No-op when the Kafka feature flag ({@code pos.people.kafka.enabled}) is off — the
 * {@link OutboxEventWriter} bean is conditional. Must be called inside the mutating transaction.
 * Employee and assignment rows carry no optimistic-lock version, so {@code aggregateVersion} is
 * the emission timestamp in epoch milliseconds (last-writer-wins hint; guard with {@code >=}).
 */
@Slf4j
@Component
public class PeopleEventPublisher {

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final String eventsTopic;
    private final String peopleContactCommandsTopic;

    public PeopleEventPublisher(
            Clock clock,
            ObjectMapper objectMapper,
            ObjectProvider<OutboxEventWriter> outboxEventWriter,
            // Same property ManifestPublisher scans event_outbox by, so an override can never
            // desync the outbox rows from the manifest computation.
            @Value("${pos.people.kafka.events-topic:people.events.v1}") String eventsTopic,
            @Value("${pos.people.kafka.people-contact-commands-topic:people-contact.commands.v1}")
                    String peopleContactCommandsTopic) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.outboxEventWriter = outboxEventWriter;
        this.eventsTopic = eventsTopic;
        this.peopleContactCommandsTopic = peopleContactCommandsTopic;
    }

    public void publishEmployeeUpdated(@NonNull Employee employee) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        EmployeeUpdatedV1 payload = new EmployeeUpdatedV1(
                employee.getId(),
                employee.getPersonId(),
                employee.getEmployeeNumber(),
                employee.getStatus() == null ? "ACTIVE" : employee.getStatus().name(),
                employee.getHireDate(),
                employee.getTerminationDate(),
                employee.getStatusEffectiveAt());
        writer.publish(
                eventsTopic,
                envelope(EmployeeUpdatedV1.EVENT_TYPE, EmployeeUpdatedV1.SCHEMA_VERSION, employee.getId(), payload));
        log.debug(
                "Queued people.employee.updated employeeId={} personId={} status={}",
                employee.getId(),
                employee.getPersonId(),
                employee.getStatus());
    }

    public void publishStaffingAssignmentUpdated(@NonNull EmployeeLocationAssignment assignment) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        Employee employee = assignment.getEmployee();
        StaffingAssignmentUpdatedV1 payload = new StaffingAssignmentUpdatedV1(
                assignment.getId(),
                employee.getId(),
                employee.getPersonId(),
                assignment.getLocationId(),
                assignment.getRole(),
                assignment.isPrimary(),
                assignment.getStatus() == null
                        ? "ACTIVE"
                        : assignment.getStatus().name(),
                assignment.getEffectiveFrom(),
                assignment.getEffectiveTo());
        writer.publish(
                eventsTopic,
                envelope(
                        StaffingAssignmentUpdatedV1.EVENT_TYPE,
                        StaffingAssignmentUpdatedV1.SCHEMA_VERSION,
                        assignment.getId(),
                        payload));
        log.debug(
                "Queued people.staffing-assignment.updated assignmentId={} locationId={}",
                assignment.getId(),
                assignment.getLocationId());
    }

    /**
     * Queue an identity upsert command toward pos-people-contact (ADR-0044 §2). The command
     * message is the plain {@code {commandType, eventId, payload}} shape the command listeners
     * use, not a fact envelope; the eventId is the receiver's idempotency key.
     */
    public void requestPersonUpsert(@NonNull PersonUpsertRequestedV1 command) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        UUID eventId = UUIDv7Generator.generate();
        String message;
        try {
            message = objectMapper.writeValueAsString(new CommandMessage(
                    PersonUpsertRequestedV1.COMMAND_TYPE,
                    eventId.toString(),
                    PersonUpsertRequestedV1.SCHEMA_VERSION,
                    command));
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to serialize person upsert command for personId: " + command.personId(), e);
        }
        writer.publishRaw(peopleContactCommandsTopic, command.personId().toString(), message);
        log.debug("Queued people-contact.person.upsert-requested personId={} eventId={}", command.personId(), eventId);
    }

    private <T> DomainEventEnvelope<T> envelope(String eventType, int schemaVersion, UUID aggregateId, T payload) {
        return DomainEventEnvelope.of(
                eventType,
                schemaVersion,
                aggregateId,
                Instant.now(clock).toEpochMilli(),
                "pos-people",
                null,
                null,
                payload,
                clock);
    }

    /** Command message shape consumed by {@code people-contact.commands.v1} (see #874 listener). */
    record CommandMessage(
            @NonNull String commandType,
            @NonNull String eventId,
            int schemaVersion,
            @NonNull Object payload) {}
}
