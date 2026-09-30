package com.positivity.workorder.internal.service;

import com.positivity.domainevents.people.EmployeeUpdatedV1;
import com.positivity.domainevents.people.PersonCredentialUpdatedV1;
import com.positivity.domainevents.people.StaffingAssignmentUpdatedV1;
import com.positivity.domainevents.peoplecontact.PersonDeletedV1;
import com.positivity.domainevents.peoplecontact.PersonUpdatedV1;
import com.positivity.domainevents.peoplecontact.UserPersonLinkRemovedV1;
import com.positivity.domainevents.peoplecontact.UserPersonLinkUpdatedV1;
import com.positivity.workorder.internal.entity.ExtEmployeeReplica;
import com.positivity.workorder.internal.entity.ExtPersonCredentialReplica;
import com.positivity.workorder.internal.entity.ExtPersonReplica;
import com.positivity.workorder.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.workorder.internal.entity.ExtUserLinkReplica;
import com.positivity.workorder.internal.entity.ProcessedEvent;
import com.positivity.workorder.internal.repository.ExtEmployeeReplicaRepository;
import com.positivity.workorder.internal.repository.ExtPersonCredentialReplicaRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes people-domain facts into this module's scheduling replicas (ADR-0044 §6, #877):
 * {@code people-contact.events.v1} feeds person names + user links, {@code people.events.v1}
 * feeds staffing assignments, employment status (#2119) and credentials (#2122). Same consumer contract as the pos-customer vehicle listener:
 * idempotent via {@code processed_events}, strictly-below stale guard (the producers'
 * aggregateVersion is an emission-timestamp LWW hint), transient errors rethrown for retry/DLQ.
 *
 * <p>Transaction shape (#2146): the handler and its {@code processed_events} mark commit together
 * in a transaction of their own ({@code REQUIRES_NEW}) rather than the listener's, so there is no
 * at-least-once window. A permanent failure rolls back only that work, and the failed record's mark
 * is written in a separate transaction; transient failures still propagate for container retry.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "workorder.kafka", name = "enabled", havingValue = "true")
public class PeopleReplicaEventsListener {
    private static final String PAYLOAD = "payload";

    private static final String AGGREGATE_VERSION = "aggregateVersion";

    static final String OWNER_PEOPLE_CONTACT = "people-contact";
    static final String OWNER_PEOPLE = "people";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtPersonReplicaRepository extPersonReplicaRepository;
    private final ExtUserLinkReplicaRepository extUserLinkReplicaRepository;
    private final ExtStaffingAssignmentReplicaRepository extStaffingAssignmentReplicaRepository;
    private final ExtEmployeeReplicaRepository extEmployeeReplicaRepository;
    private final ExtPersonCredentialReplicaRepository extPersonCredentialReplicaRepository;
    private final Counter payloadRejectedCounterPeopleContact;
    private final Counter payloadRejectedCounterPeople;

    /** One transaction for the handler and its mark, one for a failed record's mark; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public PeopleReplicaEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtPersonReplicaRepository extPersonReplicaRepository,
            ExtUserLinkReplicaRepository extUserLinkReplicaRepository,
            ExtStaffingAssignmentReplicaRepository extStaffingAssignmentReplicaRepository,
            ExtEmployeeReplicaRepository extEmployeeReplicaRepository,
            ExtPersonCredentialReplicaRepository extPersonCredentialReplicaRepository,
            ObjectProvider<MeterRegistry> meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extPersonReplicaRepository = extPersonReplicaRepository;
        this.extUserLinkReplicaRepository = extUserLinkReplicaRepository;
        this.extStaffingAssignmentReplicaRepository = extStaffingAssignmentReplicaRepository;
        this.extEmployeeReplicaRepository = extEmployeeReplicaRepository;
        this.extPersonCredentialReplicaRepository = extPersonCredentialReplicaRepository;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounterPeopleContact = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", OWNER_PEOPLE_CONTACT)
                        .tag("entity", "people-contact-events")
                        .register(registry);
        this.payloadRejectedCounterPeople = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", OWNER_PEOPLE)
                        .tag("entity", "people-events")
                        .register(registry);
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @KafkaListener(
            topics = "${workorder.kafka.people-contact-events-topic:people-contact.events.v1}",
            groupId = "${workorder.kafka.people-contact-events-consumer-group:pos-workorder-people-contact-events}")
    public void onPeopleContactEvent(@NonNull String message) {
        handle(message, OWNER_PEOPLE_CONTACT);
    }

    @KafkaListener(
            topics = "${workorder.kafka.people-events-topic:people.events.v1}",
            groupId = "${workorder.kafka.people-events-consumer-group:pos-workorder-people-events}")
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
            handlerTransaction.executeWithoutResult(_ -> {
                switch (eventType == null ? "" : eventType) {
                    case PersonUpdatedV1.EVENT_TYPE -> applyPersonUpdated(envelope);
                    case PersonDeletedV1.EVENT_TYPE -> applyPersonDeleted(envelope);
                    case UserPersonLinkUpdatedV1.EVENT_TYPE -> applyLinkUpdated(envelope);
                    case UserPersonLinkRemovedV1.EVENT_TYPE -> applyLinkRemoved(envelope);
                    case StaffingAssignmentUpdatedV1.EVENT_TYPE -> applyAssignmentUpdated(envelope);
                    case EmployeeUpdatedV1.EVENT_TYPE -> applyEmployeeUpdated(envelope);
                    case PersonCredentialUpdatedV1.EVENT_TYPE -> applyPersonCredentialUpdated(envelope);
                    default ->
                        // Ignored types still fall through to the processed_events insert below: the
                        // owner's manifest counts every fact in the window, so skipping the insert
                        // would register as replica drift and trigger a pointless replay.
                        log.debug("Ignoring {} event type={}", owner, eventType);
                }
                processedEventRepository.save(processedMark(eventId, owner));
            });
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            Counter counter = OWNER_PEOPLE_CONTACT.equals(owner)
                    ? payloadRejectedCounterPeopleContact
                    : payloadRejectedCounterPeople;
            if (counter != null) {
                counter.increment();
            }
            log.error("Rejected malformed {} event payload eventId={}: {}", owner, eventId, e.getMessage(), e);
            recordFailure(eventId, owner);
        } catch (Exception e) {
            log.warn("Skipping malformed {} event eventId={}", owner, eventId, e);
            recordFailure(eventId, owner);
        }
    }

    /** A permanently failed record's mark, in its own transaction: the handler's rolled back. */
    private void recordFailure(String eventId, String owner) {
        handlerTransaction.executeWithoutResult(_ -> processedEventRepository.save(processedMark(eventId, owner)));
    }

    private ProcessedEvent processedMark(String eventId, String owner) {
        return ProcessedEvent.builder()
                .eventId(eventId)
                .owner(owner)
                .processedAt(Instant.now(clock))
                .build();
    }

    private void applyPersonUpdated(JsonNode envelope) {
        PersonUpdatedV1 payload = objectMapper.treeToValue(envelope.path(PAYLOAD), PersonUpdatedV1.class);
        long aggregateVersion = envelope.path(AGGREGATE_VERSION).longValue(0);
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
        PersonDeletedV1 payload = objectMapper.treeToValue(envelope.path(PAYLOAD), PersonDeletedV1.class);
        extPersonReplicaRepository.deleteById(payload.personId());
    }

    private void applyLinkUpdated(JsonNode envelope) {
        UserPersonLinkUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path(PAYLOAD), UserPersonLinkUpdatedV1.class);
        long aggregateVersion = envelope.path(AGGREGATE_VERSION).longValue(0);
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
                objectMapper.treeToValue(envelope.path(PAYLOAD), UserPersonLinkRemovedV1.class);
        extUserLinkReplicaRepository.deleteById(payload.linkId());
    }

    private void applyAssignmentUpdated(JsonNode envelope) {
        StaffingAssignmentUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path(PAYLOAD), StaffingAssignmentUpdatedV1.class);
        long aggregateVersion = envelope.path(AGGREGATE_VERSION).longValue(0);
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

    /**
     * Upserts the employment-status row by employeeId (#2119). Same strictly-below stale guard as
     * the other handlers: a stored version greater than the incoming one is skipped, an equal one
     * is re-applied ({@code EmployeeUpdatedV1} documents {@code >=}, which this matches).
     */
    private void applyEmployeeUpdated(JsonNode envelope) {
        EmployeeUpdatedV1 payload = objectMapper.treeToValue(envelope.path(PAYLOAD), EmployeeUpdatedV1.class);
        long aggregateVersion = envelope.path(AGGREGATE_VERSION).longValue(0);
        ExtEmployeeReplica existing =
                extEmployeeReplicaRepository.findById(payload.employeeId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        extEmployeeReplicaRepository.save(ExtEmployeeReplica.builder()
                .employeeId(payload.employeeId())
                .personId(payload.personId())
                .status(payload.status())
                .statusEffectiveAt(payload.statusEffectiveAt())
                .terminationDate(payload.terminationDate())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
    }

    /**
     * Upserts a credential row by credentialId (#2122). Each credential is its own aggregate in the
     * owner (a renewal arrives as a new id, a revocation or supersession as a status change on its
     * own id), so this is a straight upsert under the same strictly-below stale guard as the other
     * handlers. Status is stored as received; expiry is judged on read, against the date asked about.
     */
    private void applyPersonCredentialUpdated(JsonNode envelope) {
        PersonCredentialUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path(PAYLOAD), PersonCredentialUpdatedV1.class);
        long aggregateVersion = envelope.path(AGGREGATE_VERSION).longValue(0);
        ExtPersonCredentialReplica existing = extPersonCredentialReplicaRepository
                .findById(payload.credentialId())
                .orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        extPersonCredentialReplicaRepository.save(ExtPersonCredentialReplica.builder()
                .credentialId(payload.credentialId())
                .personId(payload.personId())
                .skillId(payload.skillId())
                .skillCode(payload.skillCode())
                .competenceCode(payload.competenceCode())
                .minGvwrClass(payload.minGvwrClass())
                .maxGvwrClass(payload.maxGvwrClass())
                .issuer(payload.issuer())
                .sourceCode(payload.sourceCode())
                .sourceCredentialCode(payload.sourceCredentialCode())
                .issuedOn(payload.issuedOn())
                .expiresOn(payload.expiresOn())
                .proficiency(payload.proficiency())
                .status(payload.status())
                .evidenceRef(payload.evidenceRef())
                .supersededBy(payload.supersededBy())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
    }
}
