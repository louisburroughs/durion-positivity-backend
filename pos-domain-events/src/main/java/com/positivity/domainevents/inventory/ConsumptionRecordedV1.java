package com.positivity.domainevents.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: picked items were consumed to a workorder (ADR-0044 §6, issue #901 Phase 5.5).
 *
 * <p>Published by pos-inventory on {@code inventory.events.v1} with
 * {@code eventType = "inventory.consumption.recorded"} — one fact per consumption transaction
 * (this is an occurrence, not a snapshot: it is never re-emitted with newer state). Consumers
 * (pos-workorder) accumulate {@code quantity} per pick task into the {@code ext_pick_task}
 * replica's consumed quantity.
 *
 * <p><b>Schema v2 (odoo-parity J3, issue #1053):</b> adds the additive engine-cost fields
 * {@code unitCost}/{@code extendedCost} on each {@link Line} plus the fact-level
 * {@code costSource}. The costs are the J1 costing engine's method-derived unit cost stamped on
 * the {@code WORKORDER_CONSUMPTION} ledger entry at the moment of the movement (ADR-0048); an
 * uncosted SKU (no engine cost) carries {@code null} line costs and {@code costSource = "NONE"}.
 * Events emitted under schema v1 deserialize with these fields absent — cost-unaware consumers
 * (pos-workorder, which reads only {@code quantity}) ignore them, and cost-aware consumers must
 * tolerate their absence on a v1 event.
 *
 * @param consumptionId consumption transaction identifier (aggregate id of the fact)
 * @param workorderId workorder the items were consumed to
 * @param pickListId pick list the items were picked from
 * @param totalItemsConsumed total quantity consumed in this transaction
 * @param consumedAt consumption timestamp
 * @param lines per-pick-task consumed quantities
 * @param costSource origin of the line costs: {@code "ENGINE"} when the J1 engine costed at least
 *     one line, {@code "NONE"} when no line could be costed, {@code null} on a v1 event
 */
public record ConsumptionRecordedV1(
        @NonNull UUID consumptionId,
        @NonNull UUID workorderId,
        @Nullable UUID pickListId,
        int totalItemsConsumed,
        @Nullable Instant consumedAt,
        @NonNull List<Line> lines,
        @Nullable String costSource) {

    public static final String EVENT_TYPE = "inventory.consumption.recorded";
    public static final int SCHEMA_VERSION = 2;

    public ConsumptionRecordedV1 {
        if (consumptionId == null) {
            throw new IllegalArgumentException("consumptionId must not be null");
        }
        if (workorderId == null) {
            throw new IllegalArgumentException("workorderId must not be null");
        }
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * One consumed pick-task line.
     *
     * @param pickTaskId pick task the quantity was consumed from
     * @param skuId product/SKU consumed
     * @param quantity quantity consumed from this task
     * @param unitCost engine method-derived unit cost stamped on the consumption ledger entry
     *     (odoo-parity J3, ADR-0048); {@code null} when the SKU is uncosted or on a v1 event
     * @param extendedCost {@code unitCost x quantity} when costed, else {@code null}
     */
    public record Line(
            @NonNull UUID pickTaskId,
            @Nullable UUID skuId,
            int quantity,
            @Nullable BigDecimal unitCost,
            @Nullable BigDecimal extendedCost) {}
}
