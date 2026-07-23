package com.positivity.domainevents.order;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a sales order completed its cancellation saga (order parity plan story D1, issue #1062).
 *
 * <p>Published by pos-order on {@code order.events.v1} with
 * {@code eventType = "order.order.cancelled"} when the cancellation orchestration reaches its
 * terminal CANCELLED state (DECISION-ORDER-001..003). Downstream consumers reconcile any
 * side-effects they own (workorder termination and payment reversal have already been coordinated
 * synchronously by the saga; this fact is the durable record for read models and audit).
 *
 * @param orderId sales order identifier (also the envelope aggregateId)
 * @param orderNumber human-facing order number (null for orders created before numbering)
 * @param workorderId linked workorder cancelled by the saga, if any
 * @param paymentId payment reversed by the saga, if any
 * @param cancellationReason caller-supplied reason, if any
 * @param cancelledAt when the order reached CANCELLED
 */
public record OrderCancelledV1(
        @NonNull UUID orderId,
        @Nullable String orderNumber,
        @Nullable UUID workorderId,
        @Nullable UUID paymentId,
        @Nullable String cancellationReason,
        @NonNull Instant cancelledAt) {

    public static final String EVENT_TYPE = "order.order.cancelled";
    public static final int SCHEMA_VERSION = 1;
}
