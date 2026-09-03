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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code workorder.events.v1} into the {@code ext_workorder} replica (ADR-0044 §6,
 * #1658), so {@code GET /v1/shop-dashboard} answers from local rows instead of calling into
 * pos-workorder, which ADR-0044 R1 forbids.
 *
 * <p>Phase 3.4 consumer contract, identical to this module's four existing replica listeners:
 * {@code processed_events} idempotency inside the apply transaction, a strictly-below stale guard
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
 * <p>That notification is delivered <em>after</em> this transaction commits — see
 * {@link com.positivity.shopmanager.internal.config.WorkorderStatusChangedEventListener} — so a
 * failure in the appointment sync can neither roll back the replica write nor prevent the
 * {@code processed_events} row from landing. Handling it inline would have done both at once, and
 * a failed dedup insert means the same record is redelivered indefinitely.
 *
 * <p>Staleness is expected and fail-open by design: the dashboard is a read model over an
 * at-least-once feed with retry and backoff, so an assignment made a moment ago may not be visible
 * yet. The endpoint's OpenAPI description says so.
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

    public WorkorderEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtWorkorderReplicaRepository extWorkorderReplicaRepository,
            ApplicationEventPublisher applicationEventPublisher,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extWorkorderReplicaRepository = extWorkorderReplicaRepository;
        this.applicationEventPublisher = applicationEventPublisher;
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
    @Transactional
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
            if (WorkorderUpdatedV1.EVENT_TYPE.equals(eventType)) {
                applyWorkorderUpdated(envelope, eventId);
            } else {
                // Ignored types still fall through to the processed_events insert below: the
                // owner's manifest counts every fact in the window.
                log.debug("Ignoring workorder event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed workorder event payload eventId={}: {}", eventId, e.getMessage(), e);
        } catch (Exception e) {
            log.warn("Skipping malformed workorder event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyWorkorderUpdated(JsonNode envelope, String eventId) {
        WorkorderUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), WorkorderUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtWorkorderReplica existing =
                extWorkorderReplicaRepository.findById(payload.workorderId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        String previousStatus = existing == null ? null : existing.getStatus();

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
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());

        UUID notificationId = parseEventId(eventId);
        if (notificationId != null && payload.status() != null && !Objects.equals(previousStatus, payload.status())) {
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
                    payload.mechanicIds() == null ? List.of() : payload.mechanicIds(),
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
}
