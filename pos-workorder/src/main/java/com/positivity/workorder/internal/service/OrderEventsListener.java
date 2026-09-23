package com.positivity.workorder.internal.service;

import com.positivity.domainevents.order.OrderCompletedV1;
import com.positivity.workorder.internal.entity.ProcessedEvent;
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
 *
 * <p>Transaction shape (#2146): the handler and its {@code processed_events} mark commit together
 * in a transaction of their own ({@code REQUIRES_NEW}) rather than the listener's, so there is no
 * at-least-once window. A permanent failure rolls back only that work instead of marking a shared
 * transaction rollback-only through the {@code @Transactional} {@link WorkorderStateMachine}, and
 * the failed record's mark is written in a separate transaction; transient failures still propagate
 * for container retry.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "workorder.kafka", name = "enabled", havingValue = "true")
public class OrderEventsListener {

    static final String OWNER = "order";
    private static final String SETTLEMENT_ACTOR = "pos-order";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final WorkorderStateMachine workorderStateMachine;
    private final Counter payloadRejectedCounter;

    /** One transaction for the handler and its mark, one for a failed record's mark; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public OrderEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            WorkorderStateMachine workorderStateMachine,
            ObjectProvider<MeterRegistry> meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.workorderStateMachine = workorderStateMachine;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", OWNER)
                        .tag("entity", "order-events")
                        .register(registry);
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @KafkaListener(
            topics = "${workorder.kafka.order-events-topic:order.events.v1}",
            groupId = "${workorder.kafka.order-events-consumer-group:pos-workorder-order-events}")
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
            handlerTransaction.executeWithoutResult(_ -> {
                if (OrderCompletedV1.EVENT_TYPE.equals(eventType)) {
                    applyOrderCompleted(envelope);
                } else {
                    // Ignored types still fall through to the processed_events insert below.
                    log.debug("Ignoring order event type={} eventId={}", eventType, eventId);
                }
                processedEventRepository.save(processedMark(eventId));
            });
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed order event payload eventId={}: {}", eventId, e.getMessage(), e);
            recordFailure(eventId);
        } catch (Exception e) {
            log.warn("Skipping malformed order event eventId={}", eventId, e);
            recordFailure(eventId);
        }
    }

    /** A permanently failed record's mark, in its own transaction: the handler's rolled back. */
    private void recordFailure(String eventId) {
        handlerTransaction.executeWithoutResult(_ -> processedEventRepository.save(processedMark(eventId)));
    }

    private ProcessedEvent processedMark(String eventId) {
        return ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build();
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
