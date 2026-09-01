package com.positivity.invoice.internal.service;

import com.positivity.domainevents.workorder.WorkorderUpdatedV1;
import com.positivity.invoice.internal.entity.ExtWorkorderReplica;
import com.positivity.invoice.internal.entity.ProcessedEvent;
import com.positivity.invoice.internal.repository.ExtWorkorderReplicaRepository;
import com.positivity.invoice.internal.repository.ProcessedEventRepository;
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
 * Consumes {@code workorder.events.v1} into the {@code ext_workorder} replica (ADR-0044 §6,
 * #897), replacing the retired synchronous {@code WorkorderReferenceClient} number lookup.
 *
 * <p>Phase 3.4 consumer contract: {@code processed_events} idempotency in the apply transaction,
 * strictly-below stale guard on the emission-timestamp {@code aggregateVersion}, transient DB
 * errors rethrown for container retry/DLQ. The topic carries many workorder fact types (job time,
 * sessions, estimates) this module ignores — their eventIds are still recorded so the owner's
 * manifest reconciles.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.invoice.kafka", name = "enabled", havingValue = "true")
public class WorkorderEventsListener {

    static final String OWNER = "workorder";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtWorkorderReplicaRepository extWorkorderReplicaRepository;
    private final Counter payloadRejectedCounter;

    public WorkorderEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtWorkorderReplicaRepository extWorkorderReplicaRepository,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extWorkorderReplicaRepository = extWorkorderReplicaRepository;
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
            topics = "${pos.invoice.kafka.workorder-events-topic:workorder.events.v1}",
            groupId = "${pos.invoice.kafka.workorder-events-consumer-group:pos-invoice-workorder-events}")
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
                applyWorkorderUpdated(envelope);
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

    private void applyWorkorderUpdated(JsonNode envelope) {
        WorkorderUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), WorkorderUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
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
                // #1592: passed through verbatim, including null (older events / not yet
                // assigned) — never defaulted to updatedAt or "now", which would fabricate a
                // lag anchor the invoicing-lag report would then silently misreport.
                .workorderCreatedAt(payload.createdAt())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info("Updated ext_workorder workorderId={} version={}", payload.workorderId(), aggregateVersion);
    }
}
