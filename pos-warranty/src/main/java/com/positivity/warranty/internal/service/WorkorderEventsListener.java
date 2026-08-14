package com.positivity.warranty.internal.service;

import com.positivity.domainevents.workorder.WorkorderUpdatedV1;
import com.positivity.warranty.internal.entity.ExtWorkorderLineReplica;
import com.positivity.warranty.internal.entity.ExtWorkorderReplica;
import com.positivity.warranty.internal.entity.ProcessedEvent;
import com.positivity.warranty.internal.repository.ExtWorkorderLineReplicaRepository;
import com.positivity.warranty.internal.repository.ExtWorkorderReplicaRepository;
import com.positivity.warranty.internal.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * Consumes {@code workorder.events.v1} for sale-driven warranty auto-registration (issue #925),
 * mirroring the pos-invoice {@code WorkorderEventsListener} consumer contract:
 * {@code processed_events} idempotency in the apply transaction, transient DB errors rethrown
 * for container retry/DLQ, everything else logged and swallowed. The topic carries many
 * workorder fact types this module ignores — their eventIds are still recorded so the owner's
 * manifest reconciles.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.warranty.kafka", name = "enabled", havingValue = "true")
public class WorkorderEventsListener {

    /** Producing domain, per the repo-wide processed_events convention (manifest scans key on it). */
    static final String OWNER = "workorder";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final AutoRegistrationService autoRegistrationService;
    private final ExtWorkorderReplicaRepository extWorkorderReplicaRepository;
    private final ExtWorkorderLineReplicaRepository extWorkorderLineReplicaRepository;
    private final Counter payloadRejectedCounter;

    public WorkorderEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            AutoRegistrationService autoRegistrationService,
            ExtWorkorderReplicaRepository extWorkorderReplicaRepository,
            ExtWorkorderLineReplicaRepository extWorkorderLineReplicaRepository,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.autoRegistrationService = autoRegistrationService;
        this.extWorkorderReplicaRepository = extWorkorderReplicaRepository;
        this.extWorkorderLineReplicaRepository = extWorkorderLineReplicaRepository;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", "workorder")
                        .tag("entity", "workorder-events")
                        .register(registry);
    }

    @KafkaListener(
            topics = "${pos.warranty.kafka.workorder-events-topic:workorder.events.v1}",
            groupId = "${pos.warranty.kafka.workorder-events-consumer-group:pos-warranty-workorder-events}")
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
                WorkorderUpdatedV1 payload =
                        objectMapper.treeToValue(envelope.path("payload"), WorkorderUpdatedV1.class);
                long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
                applyWorkorderReplica(payload, aggregateVersion);
                autoRegistrationService.registerFromWorkorderSale(payload);
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

    /**
     * Maintains the read-only {@code ext_workorder} + {@code ext_workorder_line} replicas that back
     * candidate-line origin search (ADR-0044 §6, #924). Strictly-below stale guard on the fact's
     * {@code aggregateVersion}; lines are rewritten as a full replacement set per fact, matching the
     * owner's snapshot semantics.
     */
    private void applyWorkorderReplica(@NonNull WorkorderUpdatedV1 payload, long aggregateVersion) {
        ExtWorkorderReplica existing =
                extWorkorderReplicaRepository.findById(payload.workorderId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        extWorkorderReplicaRepository.save(ExtWorkorderReplica.builder()
                .workorderId(payload.workorderId())
                .workorderNumber(payload.workorderNumber())
                .status(payload.status())
                .customerId(payload.customerId())
                .vehicleId(payload.vehicleId())
                .invoiceId(payload.invoiceId())
                .workorderCreatedAt(payload.createdAt())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());

        extWorkorderLineReplicaRepository.deleteByWorkorderId(payload.workorderId());
        List<ExtWorkorderLineReplica> lines = new ArrayList<>();
        if (payload.parts() != null) {
            for (WorkorderUpdatedV1.PartLine part : payload.parts()) {
                lines.add(ExtWorkorderLineReplica.builder()
                        .workorderLineId(part.workorderLineId())
                        .workorderId(payload.workorderId())
                        .lineKind("PART")
                        .productEntityId(part.productEntityId())
                        .description(part.description())
                        .quantity(part.quantity())
                        .unitPrice(part.unitPrice())
                        .lineTotal(part.lineTotal())
                        .photoEvidenceUrl(part.photoEvidenceUrl())
                        .build());
            }
        }
        if (payload.services() != null) {
            for (WorkorderUpdatedV1.ServiceLine service : payload.services()) {
                lines.add(ExtWorkorderLineReplica.builder()
                        .workorderLineId(service.workorderLineId())
                        .workorderId(payload.workorderId())
                        .lineKind("SERVICE")
                        .description(service.description())
                        .quantity(service.quantity())
                        .unitPrice(service.unitPrice())
                        .lineTotal(service.lineTotal())
                        .photoEvidenceUrl(service.photoEvidenceUrl())
                        .build());
            }
        }
        extWorkorderLineReplicaRepository.saveAll(lines);
        log.info(
                "Updated ext_workorder workorderId={} version={} lines={}",
                payload.workorderId(),
                aggregateVersion,
                lines.size());
    }
}
