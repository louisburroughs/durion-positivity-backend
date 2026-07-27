package com.positivity.domainevents.order;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Alert: a sales-order cancellation could not complete automatically and needs a human
 * (order parity plan stories A4/D1, issues #1061/#1062).
 *
 * <p>Published by pos-order on {@code order.events.v1} with
 * {@code eventType = "order.order.cancel-review-required"} when a cancellation retry fails
 * terminally and the order lands in CANCEL_REQUIRES_MANUAL_REVIEW. Consumers route this to
 * operational alerting; the order stays reviewable and can be re-driven to CANCEL_REQUESTED once
 * the underlying failure is fixed.
 *
 * @param orderId sales order identifier (also the envelope aggregateId)
 * @param orderNumber human-facing order number (null for orders created before numbering)
 * @param workorderId linked workorder, if any
 * @param failureReason terminal failure description
 * @param occurredAt when the order entered manual review
 */
public record OrderCancelReviewRequiredV1(
        @NonNull UUID orderId,
        @Nullable String orderNumber,
        @Nullable UUID workorderId,
        @NonNull String failureReason,
        @NonNull Instant occurredAt) {

    public static final String EVENT_TYPE = "order.order.cancel-review-required";
    public static final int SCHEMA_VERSION = 1;
}
