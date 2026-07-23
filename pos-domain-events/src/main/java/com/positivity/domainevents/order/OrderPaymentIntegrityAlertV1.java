package com.positivity.domainevents.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Alert: settled payments against a sales order exceed its grand total (order parity plan story
 * C3; spec R4.4 — over-settlement is never silent).
 *
 * <p>Published by pos-order on {@code order.events.v1} with
 * {@code eventType = "order.payment.integrity-alert"} when the settlement handshake observes
 * {@code amountPaid > grandTotal}. Consumers route this to operational alerting; the order still
 * completes and the surplus is reconciled by a human (refund or correction).
 *
 * @param orderId sales order identifier (also the envelope aggregateId)
 * @param orderNumber human-facing order number
 * @param invoiceId invoice the payments were taken against
 * @param grandTotal the order's authoritative total
 * @param amountPaid net settled amount observed
 * @param detectedAt when the over-settlement was observed
 */
public record OrderPaymentIntegrityAlertV1(
        @NonNull UUID orderId,
        @Nullable String orderNumber,
        @Nullable UUID invoiceId,
        @NonNull BigDecimal grandTotal,
        @NonNull BigDecimal amountPaid,
        @NonNull Instant detectedAt) {

    public static final String EVENT_TYPE = "order.payment.integrity-alert";
    public static final int SCHEMA_VERSION = 1;
}
