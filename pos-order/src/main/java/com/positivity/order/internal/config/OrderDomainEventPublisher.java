package com.positivity.order.internal.config;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.order.OrderCancelReviewRequiredV1;
import com.positivity.domainevents.order.OrderCancelledV1;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Emits order domain facts to the outbox (ADR-0044, plan story D1). No-op while the Kafka feature
 * flag ({@code pos.order.kafka.enabled}) is off — the {@link OutboxEventWriter} bean is
 * conditional, so callers degrade gracefully. Must be called inside the mutating transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderDomainEventPublisher {

    private static final String SOURCE_SERVICE = "pos-order";

    private final Clock clock;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;

    public void publishOrderCancelled(@NonNull SalesOrder order) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        OrderCancelledV1 payload = new OrderCancelledV1(
                order.getOrderId(),
                order.getOrderNumber(),
                order.getWorkOrderId(),
                order.getPaymentId(),
                order.getCancellationReason(),
                Instant.now(clock));
        writer.publish(
                DomainTopics.events("order"),
                DomainEventEnvelope.of(
                        OrderCancelledV1.EVENT_TYPE,
                        OrderCancelledV1.SCHEMA_VERSION,
                        order.getOrderId(),
                        aggregateVersion(order),
                        SOURCE_SERVICE,
                        null,
                        SecurityContextHelper.getCurrentUsernameOrDefault("system"),
                        payload,
                        clock));
        log.debug("Queued order.order.cancelled orderId={}", order.getOrderId());
    }

    public void publishCancelReviewRequired(@NonNull SalesOrder order, @NonNull String failureReason) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        OrderCancelReviewRequiredV1 payload = new OrderCancelReviewRequiredV1(
                order.getOrderId(),
                order.getOrderNumber(),
                order.getWorkOrderId(),
                order.getPaymentId(),
                failureReason,
                Instant.now(clock));
        writer.publish(
                DomainTopics.events("order"),
                DomainEventEnvelope.of(
                        OrderCancelReviewRequiredV1.EVENT_TYPE,
                        OrderCancelReviewRequiredV1.SCHEMA_VERSION,
                        order.getOrderId(),
                        aggregateVersion(order),
                        SOURCE_SERVICE,
                        null,
                        SecurityContextHelper.getCurrentUsernameOrDefault("system"),
                        payload,
                        clock));
        log.debug("Queued order.order.cancel-review-required orderId={}", order.getOrderId());
    }

    private static long aggregateVersion(SalesOrder order) {
        return order.getVersion() == null ? 0L : order.getVersion();
    }
}
