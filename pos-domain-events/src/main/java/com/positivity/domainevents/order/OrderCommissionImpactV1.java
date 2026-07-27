package com.positivity.domainevents.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a price override was applied to an order line and may affect commission (order parity plan
 * story D3, issue #1074; spec R3.9, resolved Q7).
 *
 * <p>Published by pos-order on {@code order.events.v1} with
 * {@code eventType = "order.line.commission-impact"}. Per resolved Q7 the contract is explicit —
 * downstream commission logic must never infer impact from raw price changes: the
 * {@code affectsCommission} boolean, the discount delta, line identity, approver identity, and the
 * effective timestamp are all carried. The consuming module is the commission domain (ownership
 * verified under plan gate V3); this contract does not hardcode it.
 *
 * @param orderId order the override belongs to (also the envelope aggregateId)
 * @param orderLineId overridden line
 * @param overrideId price-override record
 * @param productId product on the line
 * @param affectsCommission explicit commission-impact flag from the override record
 * @param originalPrice unit price before the override
 * @param overridePrice unit price after the override
 * @param discountDelta originalPrice − overridePrice
 * @param requestedByUserId clerk who requested the override
 * @param approvedByUserId approver (equals requester on auto-approval)
 * @param reasonCode override reason code
 * @param effectiveAt when the override was applied/approved
 */
public record OrderCommissionImpactV1(
        @NonNull UUID orderId,
        @NonNull UUID orderLineId,
        @NonNull UUID overrideId,
        @NonNull UUID productId,
        boolean affectsCommission,
        @NonNull BigDecimal originalPrice,
        @NonNull BigDecimal overridePrice,
        @NonNull BigDecimal discountDelta,
        @NonNull UUID requestedByUserId,
        @Nullable UUID approvedByUserId,
        @NonNull String reasonCode,
        @NonNull Instant effectiveAt) {

    public static final String EVENT_TYPE = "order.line.commission-impact";
    public static final int SCHEMA_VERSION = 1;
}
