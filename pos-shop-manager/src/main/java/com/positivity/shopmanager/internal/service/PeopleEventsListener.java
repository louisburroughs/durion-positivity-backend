package com.positivity.shopmanager.internal.service;

import com.positivity.domainevents.people.EmployeeUpdatedV1;
import com.positivity.domainevents.people.StaffingAssignmentUpdatedV1;
import com.positivity.shopmanager.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.shopmanager.internal.entity.ProcessedEvent;
import com.positivity.shopmanager.internal.repository.ExtPersonReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtStaffingAssignmentReplicaRepository;
import com.positivity.shopmanager.internal.repository.ProcessedEventRepository;
import com.positivity.shopmanager.internal.service.dto.HrMechanicEvent;
import com.positivity.shopmanager.internal.service.enums.HrEventType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code people.events.v1} into the {@code ext_people_staffing_assignment} replica
 * (ADR-0044 §6, #877) and drives the mechanic projection through {@link MechanicSyncService}:
 * TECHNICIAN staffing assignments upsert/deactivate mechanics, and terminal
 * {@code people.employee.updated} statuses deactivate them. Idempotent via
 * {@code processed_events}; strictly-below stale guard; transient errors → retry/DLQ.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.shop-manager.kafka", name = "enabled", havingValue = "true")
public class PeopleEventsListener {

    static final String OWNER = "people";
    static final String TECHNICIAN_ROLE = "TECHNICIAN";
    private static final String ASSIGNMENT_STATUS_ACTIVE = "ACTIVE";
    /** Employee statuses that end a person's mechanic record; leave states do not. */
    private static final Set<String> DEACTIVATING_EMPLOYEE_STATUSES = Set.of("TERMINATED", "DISABLED");

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtStaffingAssignmentReplicaRepository assignmentReplicaRepository;
    private final MechanicSyncService mechanicSyncService;
    private final ExtPersonReplicaRepository personReplicaRepository;
    private final Counter payloadRejectedCounter;

    public PeopleEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtStaffingAssignmentReplicaRepository assignmentReplicaRepository,
            MechanicSyncService mechanicSyncService,
            ExtPersonReplicaRepository personReplicaRepository,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.assignmentReplicaRepository = assignmentReplicaRepository;
        this.mechanicSyncService = mechanicSyncService;
        this.personReplicaRepository = personReplicaRepository;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", OWNER)
                        .tag("entity", "people-events")
                        .register(registry);
    }

    @KafkaListener(
            topics = "${pos.shop-manager.kafka.people-events-topic:people.events.v1}",
            groupId = "${pos.shop-manager.kafka.people-events-consumer-group:pos-shop-manager-people-events}")
    @Transactional
    public void onPeopleEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable people event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping people event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            switch (eventType == null ? "" : eventType) {
                case StaffingAssignmentUpdatedV1.EVENT_TYPE -> applyStaffingAssignmentUpdated(envelope, eventId);
                case EmployeeUpdatedV1.EVENT_TYPE -> applyEmployeeUpdated(envelope, eventId);
                default -> log.debug("Ignoring people event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed people event payload eventId={}: {}", eventId, e.getMessage(), e);
        } catch (Exception e) {
            log.warn("Skipping malformed people event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyStaffingAssignmentUpdated(@NonNull JsonNode envelope, @NonNull String eventId)
            throws DatabindException {
        StaffingAssignmentUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), StaffingAssignmentUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtStaffingAssignmentReplica existing =
                assignmentReplicaRepository.findById(payload.assignmentId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            // Stale snapshot: neither the replica nor the mechanic projection may move
            // backwards — skip both, matching the stale-guard intent end to end.
            return;
        }
        assignmentReplicaRepository.save(ExtStaffingAssignmentReplica.builder()
                .assignmentId(payload.assignmentId())
                .employeeId(payload.employeeId())
                .personId(payload.personId())
                .locationId(payload.locationId())
                .role(payload.role())
                .primary(payload.primary())
                .status(payload.status())
                .effectiveFrom(payload.effectiveFrom())
                .effectiveTo(payload.effectiveTo())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        syncMechanicFromAssignment(payload, aggregateVersion, eventId);
    }

    /**
     * Drives the dormant mechanic projection from TECHNICIAN staffing assignments (the HR feed
     * MechanicSyncService was built for): an ACTIVE assignment upserts the mechanic — names
     * joined from the {@code ext_people_contact_person} replica when present, and left for a
     * later event to fill when not (the sync service preserves fields the event doesn't carry) —
     * and an ended assignment deactivates the mechanic only when the person holds no other
     * ACTIVE TECHNICIAN assignment (the replica was updated with this event first, so the
     * remaining-actives check sees current state).
     */
    private void syncMechanicFromAssignment(
            @NonNull StaffingAssignmentUpdatedV1 payload, long aggregateVersion, @NonNull String eventId) {
        if (!TECHNICIAN_ROLE.equals(payload.role())) {
            return;
        }
        if (ASSIGNMENT_STATUS_ACTIVE.equals(payload.status())) {
            HrMechanicEvent.Payload names = personReplicaRepository
                    .findById(payload.personId())
                    .map(person -> HrMechanicEvent.Payload.builder()
                            .firstName(person.getFirstName())
                            .lastName(person.getLastName())
                            .build())
                    .orElse(null);
            mechanicSyncService.processHrEvent(
                    hrEvent(eventId, HrEventType.MECHANIC_UPSERTED, payload.personId(), aggregateVersion, names));
        } else if (!hasActiveTechnicianAssignment(payload.personId())) {
            mechanicSyncService.processHrEvent(
                    hrEvent(eventId, HrEventType.MECHANIC_DEACTIVATED, payload.personId(), aggregateVersion, null));
        }
    }

    /**
     * Ends a person's mechanic record when HR terminates or disables the employee. Activation
     * and routine refresh stay assignment-driven; employee facts carry no names or skills, so
     * only the terminal statuses act here. A deactivation for a person who never was a mechanic
     * is a logged no-op in the sync service.
     */
    private void applyEmployeeUpdated(@NonNull JsonNode envelope, @NonNull String eventId) throws DatabindException {
        EmployeeUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), EmployeeUpdatedV1.class);
        if (!DEACTIVATING_EMPLOYEE_STATUSES.contains(payload.status())) {
            log.debug("Ignoring people employee event status={} eventId={}", payload.status(), eventId);
            return;
        }
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        mechanicSyncService.processHrEvent(
                hrEvent(eventId, HrEventType.MECHANIC_DEACTIVATED, payload.personId(), aggregateVersion, null));
    }

    private boolean hasActiveTechnicianAssignment(@NonNull UUID personId) {
        return assignmentReplicaRepository.findByPersonIdAndStatus(personId, ASSIGNMENT_STATUS_ACTIVE).stream()
                .anyMatch(assignment -> TECHNICIAN_ROLE.equals(assignment.getRole()));
    }

    private HrMechanicEvent hrEvent(
            @NonNull String eventId,
            @NonNull HrEventType eventType,
            @NonNull UUID personId,
            long aggregateVersion,
            HrMechanicEvent.Payload payload) {
        return HrMechanicEvent.builder()
                .eventId(UUID.fromString(eventId))
                .eventType(eventType)
                .personId(personId.toString())
                .version(aggregateVersion)
                .occurredAt(Instant.now(clock))
                .payload(payload)
                .build();
    }
}
