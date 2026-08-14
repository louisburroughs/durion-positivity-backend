package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code order.events.v1} register-session close facts into over/short GL postings
 * (odoo-parity G3, issue #1083).
 *
 * <p>Same reliability contract as {@link InventoryEventsListener}: idempotent via {@code
 * processed_events} in the ingest transaction, transient DB errors and posting failures propagate
 * unwrapped for container retry / DLQ (ADR-0044 §4), malformed payloads logged and marked processed
 * so a poison record never blocks the partition. Only {@code order.session.closed} events are
 * handled; the topic's other (high-volume) fact types are ignored without recording their eventIds.
 *
 * <p>Posting itself (idempotent on sessionId via posting key, period-gated, accounts resolved
 * through the {@code REGISTER_OVER_SHORT} posting category, zero-variance posts nothing) lives in
 * {@link RegisterOverShortPostingService}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.accounting.kafka", name = "enabled", havingValue = "true")
public class OrderEventsListener {

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final RegisterOverShortPostingService registerOverShortPostingService;
    private final Counter payloadRejectedCounter;

    public OrderEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            RegisterOverShortPostingService registerOverShortPostingService,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.registerOverShortPostingService = registerOverShortPostingService;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", "order")
                        .tag("entity", "order-events")
                        .register(registry);
    }

    @KafkaListener(
            topics = "${pos.accounting.kafka.order-events-topic:order.events.v1}",
            groupId = "pos-accounting-order-events")
    @Transactional
    public void onOrderEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable order event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        if (!RegisterSessionClosedV1.EVENT_TYPE.equals(eventType)) {
            log.debug("Ignoring order event type={}", eventType);
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping register-session-closed event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate register-session-closed event eventId={}", eventId);
            return;
        }

        // Only deserialization failures are terminal for this record — skip and mark processed so a
        // malformed payload does not poison the partition. Anything thrown by posting (transient DB
        // errors, period gate, unexpected failures) propagates unwrapped for container retry / DLQ.
        RegisterSessionClosedV1 fact;
        try {
            fact = objectMapper.treeToValue(envelope.path("payload"), RegisterSessionClosedV1.class);
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error(
                    "Rejected malformed register-session-closed event payload eventId={}: {}",
                    eventId,
                    e.getMessage(),
                    e);
            markProcessed(eventId);
            return;
        } catch (Exception e) {
            log.warn("Skipping malformed register-session-closed event eventId={}", eventId, e);
            markProcessed(eventId);
            return;
        }

        registerOverShortPostingService.postOverShort(fact);
        markProcessed(eventId);
    }

    private void markProcessed(@NonNull String eventId) {
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .processedAt(Instant.now(clock))
                .build());
    }
}
