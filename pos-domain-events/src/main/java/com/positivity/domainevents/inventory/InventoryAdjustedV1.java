package com.positivity.domainevents.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a cycle-count adjustment or a manual adjustment request was posted to the inventory
 * ledger (odoo-parity J3, issues #2186 / #2190).
 *
 * <p>Published by pos-inventory on {@code inventory.events.v1} with
 * {@code eventType = "inventory.adjustment.posted"}, envelope {@code aggregateId = adjustmentId}
 * — one fact per posted adjustment (an occurrence, never re-emitted). pos-accounting consumes it
 * to post the adjustment journal entry through the {@code INVENTORY_ADJUSTMENT} posting category
 * (issue #2191). One fact covers both document kinds (spec D1): the accounting treatment is
 * identical and {@code adjustmentKind} is a traceability dimension only.
 *
 * <p><b>Cost semantics:</b> {@code unitCost} is the J1 costing engine's method-derived unit cost
 * stamped on the posted ledger entry (ADR-0048 §3), not the create-time snapshot used for the
 * value-based approval tier. {@code costSource} is the resolved costing method ({@code "AVERAGE"}
 * or {@code "STANDARD"}) when the engine costed the movement, or {@code "NONE"} when the SKU was
 * uncosted ({@code unitCost} is then {@code null}); an uncosted fact is record-and-skip for the
 * accounting consumer (spec D6). Consumers never infer, recompute or substitute a cost.
 *
 * @param adjustmentId cycle-count adjustment or adjustment request identifier — the value the
 *     ledger row carries as {@code adjustmentId} (aggregate id of the fact)
 * @param adjustmentKind {@code CYCLE_COUNT} or {@code MANUAL_ADJUSTMENT}
 * @param ledgerEventType the posted ledger event type ({@code COUNT_VARIANCE_IN},
 *     {@code COUNT_VARIANCE_OUT}, {@code ADJUSTMENT_IN}, {@code ADJUSTMENT_OUT}); traceability
 *     only, never used to build the journal entry
 * @param ledgerEntryId the posted inventory ledger entry
 * @param sku stock-item identifier (ledger stock-item string)
 * @param locationId posting location, {@code null} only for a location-less variance
 * @param taskId cycle-count task, when any
 * @param reasonCode adjustment reason code; carried into the journal entry description only
 * @param quantityDelta signed, non-zero on-hand change: positive = gain (on-hand up), negative =
 *     loss (on-hand down); decimal-capable per ADR-0055
 * @param unitCost engine method-derived unit cost at posting, {@code null} when uncosted
 * @param costSource origin of the cost: {@code "AVERAGE"}/{@code "STANDARD"} (engine method) or
 *     {@code "NONE"} (uncosted)
 * @param occurredAt when the adjustment was posted to the ledger (business time)
 */
public record InventoryAdjustedV1(
        @NonNull UUID adjustmentId,
        @NonNull String adjustmentKind,
        @NonNull String ledgerEventType,
        @NonNull UUID ledgerEntryId,
        @NonNull String sku,
        @Nullable UUID locationId,
        @Nullable UUID taskId,
        @NonNull String reasonCode,
        @NonNull BigDecimal quantityDelta,
        @Nullable BigDecimal unitCost,
        @NonNull String costSource,
        @NonNull Instant occurredAt) {

    public static final String EVENT_TYPE = "inventory.adjustment.posted";
    public static final int SCHEMA_VERSION = 1;

    public static final String KIND_CYCLE_COUNT = "CYCLE_COUNT";
    public static final String KIND_MANUAL_ADJUSTMENT = "MANUAL_ADJUSTMENT";

    public InventoryAdjustedV1 {
        if (adjustmentId == null) {
            throw new IllegalArgumentException("adjustmentId must not be null");
        }
        if (adjustmentKind == null || adjustmentKind.isBlank()) {
            throw new IllegalArgumentException("adjustmentKind must not be blank");
        }
        if (ledgerEventType == null || ledgerEventType.isBlank()) {
            throw new IllegalArgumentException("ledgerEventType must not be blank");
        }
        if (ledgerEntryId == null) {
            throw new IllegalArgumentException("ledgerEntryId must not be null");
        }
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        if (quantityDelta == null || quantityDelta.signum() == 0) {
            throw new IllegalArgumentException("quantityDelta must be non-zero");
        }
        if (costSource == null || costSource.isBlank()) {
            throw new IllegalArgumentException("costSource must not be blank");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt must not be null");
        }
    }
}
