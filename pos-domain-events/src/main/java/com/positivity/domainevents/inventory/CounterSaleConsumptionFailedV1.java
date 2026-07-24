package com.positivity.domainevents.inventory;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Alert: a counter-sale stock consumption could not be posted (order parity story H2, #1079;
 * order-spec R8.2/R8.3 — consumption failures alert but never affect the completed sale, the
 * Odoo failed-pickings analog).
 *
 * <p>Published by pos-inventory on {@code inventory.events.v1} with
 * {@code eventType = "inventory.counter-sale.consumption-failed"} when a fulfillable
 * {@code order.order.completed} line cannot post its GOODS_ISSUE movement (e.g. insufficient
 * stock — which doubles as the fulfillment-pending signal for backordered lines — or an unknown
 * stock item). Operational alerting reconciles the physical stock truth by hand.
 *
 * @param orderId completed sales order (also the envelope aggregateId)
 * @param orderNumber human-facing order number
 * @param orderLineId order line whose consumption failed
 * @param sku stock item the movement targeted
 * @param quantity units that could not be issued
 * @param locationId shop location from the order event
 * @param reason failure detail
 * @param occurredAt when the failure was observed
 */
public record CounterSaleConsumptionFailedV1(
        @NonNull UUID orderId,
        @Nullable String orderNumber,
        @NonNull UUID orderLineId,
        @NonNull String sku,
        int quantity,
        @Nullable UUID locationId,
        @NonNull String reason,
        @NonNull Instant occurredAt) {

    public static final String EVENT_TYPE = "inventory.counter-sale.consumption-failed";
    public static final int SCHEMA_VERSION = 1;
}
