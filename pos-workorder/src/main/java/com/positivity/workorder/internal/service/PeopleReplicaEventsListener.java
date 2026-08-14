package com.positivity.workorder.internal.service;

import com.positivity.domainevents.people.StaffingAssignmentUpdatedV1;
import com.positivity.domainevents.peoplecontact.PersonDeletedV1;
import com.positivity.domainevents.peoplecontact.PersonUpdatedV1;
import com.positivity.domainevents.peoplecontact.UserPersonLinkRemovedV1;
import com.positivity.domainevents.peoplecontact.UserPersonLinkUpdatedV1;
import com.positivity.workorder.internal.entity.ExtPersonReplica;
import com.positivity.workorder.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.workorder.internal.entity.ExtUserLinkReplica;
import com.positivity.workorder.internal.entity.ProcessedEvent;
import com.positivity.workorder.internal.repository.ExtPersonReplicaRepository;
import com.positivity.workorder.internal.repository.ExtStaffingAssignmentReplicaRepository;
import com.positivity.workorder.internal.repository.ExtUserLinkReplicaRepository;
import com.positivity.workorder.internal.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
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
 * Consumes people-domain facts into this module's scheduling replicas (ADR-0044 §6, #877):
 * {@code people-contact.events.v1} feeds person names + user links, {@code people.events.v1}
 * feeds staffing assignments. Same consumer contract as the pos-customer vehicle listener:
 * idempotent via {@code processed_events}, strictly-below stale guard (the producers'
 * aggregateVersion is an emission-timestamp LWW hint), transient errors rethrown for retry/DLQ.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "workorder.kafka", name = "enabled", havingValue = "true")
public class PeopleReplicaEventsListener {

    static final String OWNER_PEOPLE_CONTACT = "people-contact";
    static final String OWNER_PEOPLE = "people";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtPersonReplicaRepository extPersonReplicaRepository;
    private final ExtUserLinkReplicaRepository extUserLinkReplicaRepository;
    private final ExtStaffingAssignmentReplicaRepository extStaffingAssignmentReplicaRepository;
    private final Counter payloadRejectedCounter;

    public PeopleReplicaEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtPersonReplicaRepository extPersonReplicaRepository,
            ExtUserLinkReplicaRepository extUserLinkReplicaRepository,
            ExtStaffingAssignmentReplicaRepository extStaffingAssignmentReplicaRepository,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extPersonReplicaRepository = extPersonReplicaRepository;
        this.extUserLinkReplicaRepository = extUserLinkReplicaRepository;
        this.extStaffingAssignmentReplicaRepository = extStaffingAssignmentReplicaRepository;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", "people")
                        .tag("entity", "people-events")
                        .register(registry);
    }

    @KafkaListener(
            topics = "${workorder.kafka.people-contact-events-topic:people-contact.events.v1}",
            groupId = "${workorder.kafka.people-contact-events-consumer-group:pos-workorder-people-contact-events}")
    @Transactional
    public void onPeopleContactEvent(@NonNull String message) {
        handle(message, OWNER_PEOPLE_CONTACT);
    }

    @KafkaListener(
            topics = "${workorder.kafka.people-events-topic:people.events.v1}",
            groupId = "${workorder.kafka.people-events-consumer-group:pos-workorder-people-events}")
    @Transactional
    public void onPeopleEvent(@NonNull String message) {
        handle(message, OWNER_PEOPLE);
    }

    private void handle(String message, String owner) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable {} event: {}", owner, message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping {} event without eventId: {}", owner, message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            switch (eventType == null ? "" : eventType) {
                case PersonUpdatedV1.EVENT_TYPE -> applyPersonUpdated(envelope);
                case PersonDeletedV1.EVENT_TYPE -> applyPersonDeleted(envelope);
                case UserPersonLinkUpdatedV1.EVENT_TYPE -> applyLinkUpdated(envelope);
                case UserPersonLinkRemovedV1.EVENT_TYPE -> applyLinkRemoved(envelope);
                case StaffingAssignmentUpdatedV1.EVENT_TYPE -> applyAssignmentUpdated(envelope);
                default ->
                    // Ignored types still fall through to the processed_events insert below: the
                    // owner's manifest counts every fact in the window, so skipping the insert
                    // would register as replica drift and trigger a pointless replay.
                    log.debug("Ignoring {} event type={}", owner, eventType);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed {} event payload eventId={}: {}", owner, eventId, e.getMessage(), e);
        } catch (Exception e) {
            log.warn("Skipping malformed {} event eventId={}", owner, eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(owner)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyPersonUpdated(JsonNode envelope) {
        PersonUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), PersonUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtPersonReplica existing =
                extPersonReplicaRepository.findById(payload.personId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        extPersonReplicaRepository.save(ExtPersonReplica.builder()
                .personId(payload.personId())
                .firstName(payload.firstName())
                .lastName(payload.lastName())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
    }

    private void applyPersonDeleted(JsonNode envelope) {
        PersonDeletedV1 payload = objectMapper.treeToValue(envelope.path("payload"), PersonDeletedV1.class);
        extPersonReplicaRepository.deleteById(payload.personId());
    }

    private void applyLinkUpdated(JsonNode envelope) {
        UserPersonLinkUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), UserPersonLinkUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtUserLinkReplica existing =
                extUserLinkReplicaRepository.findById(payload.linkId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        extUserLinkReplicaRepository.save(ExtUserLinkReplica.builder()
                .linkId(payload.linkId())
                .personId(payload.personId())
                .username(payload.username())
                .status(payload.status())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
    }

    private void applyLinkRemoved(JsonNode envelope) {
        UserPersonLinkRemovedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), UserPersonLinkRemovedV1.class);
        extUserLinkReplicaRepository.deleteById(payload.linkId());
    }

    private void applyAssignmentUpdated(JsonNode envelope) {
        StaffingAssignmentUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), StaffingAssignmentUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtStaffingAssignmentReplica existing = extStaffingAssignmentReplicaRepository
                .findById(payload.assignmentId())
                .orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        extStaffingAssignmentReplicaRepository.save(ExtStaffingAssignmentReplica.builder()
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
    }
}
