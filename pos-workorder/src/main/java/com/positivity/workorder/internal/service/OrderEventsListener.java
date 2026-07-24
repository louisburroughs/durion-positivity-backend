package com.positivity.workorder.internal.service;

import com.positivity.domainevents.order.OrderCompletedV1;
import com.positivity.workorder.internal.entity.ProcessedEvent;
import com.positivity.workorder.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code order.events.v1} completion facts and finalizes the settled workorder back to
 * COMPLETED (odoo-parity story E3, issue #1084, spec R7.3). When a counter order that fronted a
 * workorder settlement completes, {@code order.order.completed} carries the {@code workorderId};
 * this listener drives the workorder's own state machine to COMPLETED — there is no synchronous
 * write from pos-order.
 *
 * <p>Consumer contract (mirrors {@link InvoiceEventsListener}): {@code processed_events}
 * idempotency (owner {@code order}) in the apply transaction — a redelivered fact is a no-op — with
 * transient DB errors rethrown for container retry / DLQ and malformed payloads logged and marked
 * processed so a poison record never blocks the partition. Non-workorder completions (no
 * {@code workorderId}) and other order fact types are ignored, but still record their eventIds so
 * the owner's manifest reconciles.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "workorder.kafka", name = "enabled", havingValue = "true")
public class OrderEventsListener {

    static final String OWNER = "order";
    private static final String SETTLEMENT_ACTOR = "pos-order";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final WorkorderStateMachine workorderStateMachine;

    @KafkaListener(
            topics = "${workorder.kafka.order-events-topic:order.events.v1}",
            groupId = "${workorder.kafka.order-events-consumer-group:pos-workorder-order-events}")
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
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping order event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            if (OrderCompletedV1.EVENT_TYPE.equals(eventType)) {
                applyOrderCompleted(envelope);
            } else {
                // Ignored types still fall through to the processed_events insert below.
                log.debug("Ignoring order event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed order event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyOrderCompleted(JsonNode envelope) {
        OrderCompletedV1 payload = objectMapper.treeToValue(envelope.path("payload"), OrderCompletedV1.class);
        if (payload.workorderId() == null) {
            log.debug("Order {} completed with no workorderId; nothing to finalize", payload.orderId());
            return;
        }
        workorderStateMachine.finalizeFromOrderSettlement(payload.workorderId(), payload.orderId(), SETTLEMENT_ACTOR);
    }
}
