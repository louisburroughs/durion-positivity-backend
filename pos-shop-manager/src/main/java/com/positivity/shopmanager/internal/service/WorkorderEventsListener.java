package com.positivity.shopmanager.internal.service;

import com.positivity.domainevents.workorder.WorkorderUpdatedV1;
import com.positivity.shopmanager.internal.dto.WorkorderStatusChangedEvent;
import com.positivity.shopmanager.internal.entity.ExtWorkorderReplica;
import com.positivity.shopmanager.internal.entity.ProcessedEvent;
import com.positivity.shopmanager.internal.enums.ShopDashboardUnitType;
import com.positivity.shopmanager.internal.repository.ExtWorkorderReplicaRepository;
import com.positivity.shopmanager.internal.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
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
 * Consumes {@code workorder.events.v1} into the {@code ext_workorder} replica (ADR-0044 §6,
 * #1658), so {@code GET /v1/shop-dashboard} answers from local rows instead of calling into
 * pos-workorder, which ADR-0044 R1 forbids.
 *
 * <p>Phase 3.4 consumer contract, identical to this module's four existing replica listeners:
 * {@code processed_events} idempotency, a strictly-below stale guard
 * on the envelope's {@code aggregateVersion}, transient DB errors rethrown for container
 * retry/DLQ, malformed payloads swallowed but still counted so the owner's manifest reconciles.
 *
 * <p>This listener is the only writer of {@code ext_workorder} (R3). It also raises the in-process
 * {@link WorkorderStatusChangedEvent} — but <em>only</em> when the applied fact carries a status
 * different from the one already held. pos-workorder emits one snapshot fact per business
 * transaction that touches a workorder, most of which do not move its status; republishing every
 * one as a status change would append a duplicate entry to the linked appointment's status
 * timeline on each unrelated edit. Comparing against the row being replaced is what makes the
 * existing {@link WorkorderStatusEventService} appointment sync safe to feed from this firehose,
 * rather than standing up a second, parallel consumption path for it.
 *
 * <p>That notification is delivered <em>after</em> the handler's transaction commits — see
 * {@link com.positivity.shopmanager.internal.config.WorkorderStatusChangedEventListener} — so a
 * failure in the appointment sync can neither roll back the replica write nor prevent the
 * {@code processed_events} row from landing. Handling it inline would have done both at once, and
 * a failed dedup insert means the same record is redelivered indefinitely.
 *
 * <p>Staleness is expected and fail-open by design: the dashboard is a read model over an
 * at-least-once feed with retry and backoff, so an assignment made a moment ago may not be visible
 * yet. The endpoint's OpenAPI description says so.
 *
 * <p><strong>Transaction shape (#2146).</strong> The listener method is not {@code @Transactional}:
 * the handler's work and the {@code processed_events} mark commit together in a
 * {@code REQUIRES_NEW} transaction of their own, so neither lands without the other. A permanent
 * failure rolls back only that work and is recorded in a separate transaction, instead of
 * poisoning a shared transaction whose commit then throws and sends the record through retry and
 * dead-lettering. Transient database errors still propagate, unrecorded, for container retry.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.shop-manager.kafka", name = "enabled", havingValue = "true")
public class WorkorderEventsListener {

    static final String OWNER = "workorder";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtWorkorderReplicaRepository extWorkorderReplicaRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final Counter payloadRejectedCounter;

    /** Runs the handler with its processed mark, and records a failure; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public WorkorderEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtWorkorderReplicaRepository extWorkorderReplicaRepository,
            ApplicationEventPublisher applicationEventPublisher,
            ObjectProvider<MeterRegistry> meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extWorkorderReplicaRepository = extWorkorderReplicaRepository;
        this.applicationEventPublisher = applicationEventPublisher;
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", OWNER)
                        .tag("entity", "workorder-events")
                        .register(registry);
    }

    @KafkaListener(
            topics = "${pos.shop-manager.kafka.workorder-events-topic:workorder.events.v1}",
            groupId = "${pos.shop-manager.kafka.workorder-events-consumer-group:pos-shop-manager-workorder-events}")
    public void onWorkorderEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable workorder event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping workorder event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            handlerTransaction.executeWithoutResult(_ -> {
                if (WorkorderUpdatedV1.EVENT_TYPE.equals(eventType)) {
                    applyWorkorderUpdated(envelope, eventId);
                } else {
                    // Ignored types still fall through to the processed_events insert below: the
                    // owner's manifest counts every fact in the window.
                    log.debug("Ignoring workorder event type={} eventId={}", eventType, eventId);
                }
                processedEventRepository.save(processedMark(eventId));
            });
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed workorder event payload eventId={}: {}", eventId, e.getMessage(), e);
            recordFailed(eventId);
        } catch (Exception e) {
            log.warn("Skipping malformed workorder event eventId={}", eventId, e);
            recordFailed(eventId);
        }
    }

    private ProcessedEvent processedMark(String eventId) {
        return ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build();
    }

    /** Records a permanently failed event in a transaction of its own; see the class doc. */
    private void recordFailed(String eventId) {
        handlerTransaction.executeWithoutResult(_ -> processedEventRepository.save(processedMark(eventId)));
    }

    private void applyWorkorderUpdated(JsonNode envelope, String eventId) {
        JsonNode payloadNode = envelope.path("payload");
        WorkorderUpdatedV1 payload = objectMapper.treeToValue(payloadNode, WorkorderUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtWorkorderReplica existing =
                extWorkorderReplicaRepository.findById(payload.workorderId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        String previousStatus = existing == null ? null : existing.getStatus();

        // The actual-time block (workStartedAt/completedAt/expectedEndAt) is additive within
        // schema v1 (#2021): a fact serialized by a pre-#2021 producer has no such field at all,
        // and rebuilding the row straight from the payload would read that absence as an explicit
        // null and erase actuals already replicated during a rolling deploy or replay of an older
        // stored event (#2023 F4). mergeField keeps the existing value when the field is genuinely
        // absent from the raw envelope, and only clears it when the fact carries an explicit JSON
        // null.
        extWorkorderReplicaRepository.save(ExtWorkorderReplica.builder()
                .workorderId(payload.workorderId())
                .workorderNumber(payload.workorderNumber())
                .status(payload.status())
                // The owner carries both: shopId names the owning shop, locationId the site the
                // work occupies. Fall back to shopId so a fact published before pos-workorder
                // started setting locationId still lands at a site the dashboard can scope on.
                .locationId(payload.locationId() != null ? payload.locationId() : payload.shopId())
                .vehicleId(payload.vehicleId())
                .customerId(payload.customerId())
                .resourceId(payload.resourceId())
                .resourceType(payload.resourceType())
                .mechanicIds(serializeMechanicIds(payload.mechanicIds()))
                .promisedAt(payload.promisedAt())
                .scheduledDate(payload.scheduledDate())
                .workStartedAt(mergeField(
                        payloadNode,
                        "workStartedAt",
                        payload.workStartedAt(),
                        existing == null ? null : existing.getWorkStartedAt()))
                .completedAt(mergeField(
                        payloadNode,
                        "completedAt",
                        payload.completedAt(),
                        existing == null ? null : existing.getCompletedAt()))
                .expectedEndAt(mergeField(
                        payloadNode,
                        "expectedEndAt",
                        payload.expectedEndAt(),
                        existing == null ? null : existing.getExpectedEndAt()))
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());

        UUID notificationId = parseEventId(eventId);
        if (notificationId != null && payload.status() != null && !Objects.equals(previousStatus, payload.status())) {
            // Read the getter once (S2637): payload.mechanicIds() may be null while the event's
            // mechanicIds is @NonNull, and re-calling the getter after the null check leaves static
            // analysis unable to tell the two calls return the same value.
            List<UUID> mechanicIds = payload.mechanicIds();
            applicationEventPublisher.publishEvent(new WorkorderStatusChangedEvent(
                    notificationId,
                    payload.workorderId(),
                    payload.status(),
                    Instant.now(clock),
                    null,
                    payload.workorderNumber(),
                    payload.locationId() != null ? payload.locationId() : payload.shopId(),
                    payload.resourceId(),
                    parseResourceType(payload.resourceType()),
                    mechanicIds == null ? List.of() : mechanicIds,
                    payload.vehicleId(),
                    payload.promisedAt(),
                    payload.scheduledDate()));
        }
    }

    /**
     * Stores the technician ids in the owner's own JSON-array shape. A normalized child table would
     * be this module asserting structure over a fact it does not own; the replica keeps the
     * snapshot verbatim and the read path parses it.
     */
    private @Nullable String serializeMechanicIds(@Nullable List<UUID> mechanicIds) {
        if (mechanicIds == null || mechanicIds.isEmpty()) {
            return null;
        }
        return objectMapper.writeValueAsString(
                mechanicIds.stream().map(UUID::toString).toList());
    }

    /**
     * The envelope eventId is a UUIDv7 string; a producer that ever sends a non-UUID id still gets
     * its replica row applied, and only the in-process notification is skipped.
     */
    private @Nullable UUID parseEventId(String eventId) {
        try {
            return UUID.fromString(eventId);
        } catch (IllegalArgumentException e) {
            log.warn("Workorder event id '{}' is not a UUID; status notification not raised", eventId);
            return null;
        }
    }

    private @Nullable ShopDashboardUnitType parseResourceType(@Nullable String resourceType) {
        if (resourceType == null || resourceType.isBlank()) {
            return null;
        }
        try {
            return ShopDashboardUnitType.valueOf(resourceType);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown workorder resourceType '{}' from the owner; treating as unassigned kind", resourceType);
            return null;
        }
    }

    /**
     * Distinguishes a field <em>absent</em> from the raw envelope (a pre-#2021 producer that
     * predates the actual-time block entirely) from one carrying an <em>explicit</em> JSON
     * {@code null} (#2023 F4). {@code JsonNode.has} is true for either a present non-null value or
     * an explicit {@code null} node, and false only when the field is missing outright — exactly
     * the "was this field ever serialized" question a rolling deploy or replay of an older stored
     * event needs answered before applying it. Absent keeps whatever this replica already holds;
     * present (including an explicit null) always takes {@code newValue}, clearing the column when
     * that is what the fact says.
     */
    private <T> @Nullable T mergeField(
            JsonNode payloadNode, String fieldName, @Nullable T newValue, @Nullable T existingValue) {
        return payloadNode.has(fieldName) ? newValue : existingValue;
    }
}
