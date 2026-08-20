package com.positivity.domainevents.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a SKU's inventory unit cost was manually revalued (odoo-parity J4, issue #1054; Odoo
 * {@code product.value} / manual {@code stock.valuation.layer} analog).
 *
 * <p>Published by pos-inventory on {@code inventory.events.v1} with
 * {@code eventType = "inventory.product-value.changed"} — one fact per applied revaluation (this is
 * an occurrence, not a snapshot: it is never re-emitted). pos-accounting consumes it to post the
 * revaluation journal entry for the value delta.
 *
 * <p>The revaluation corrects the SKU's configured standard price ({@code costingMethod = STANDARD})
 * or its running weighted average ({@code costingMethod = AVERAGE}). {@code totalValueDelta} is the
 * signed inventory value change — {@code (newUnitCost − previousUnitCost) × onHandQuantity} — at the
 * on-hand quantity the costing engine had costed at the moment the revaluation was applied. A
 * positive delta is a write-up, a negative delta a write-down. {@code previousUnitCost} is
 * {@code null} when the SKU carried no prior cost of the resolved method (the baseline is treated as
 * zero for the delta).
 *
 * @param revaluationId revaluation document identifier (aggregate id of the fact)
 * @param sku stock-item identifier that was revalued (ledger stock-item string)
 * @param costingMethod resolved costing method whose cost was corrected ({@code "STANDARD"} or
 *     {@code "AVERAGE"})
 * @param previousUnitCost unit cost before the revaluation, {@code null} when the SKU was uncosted
 * @param newUnitCost corrected unit cost after the revaluation
 * @param onHandQuantity on-hand quantity the engine had costed at revaluation time (delta basis);
 *     decimal since ADR-0055 (#1414) — it is the ledger quantity the value delta multiplies
 * @param totalValueDelta signed inventory value change {@code (newUnitCost − previousUnitCost) ×
 *     onHandQuantity}
 * @param reason operator-supplied justification for the correction
 * @param actor username of the actor whose approval applied the revaluation
 * @param occurredAt when the revaluation was applied
 */
public record ProductValueChangedV1(
        @NonNull UUID revaluationId,
        @NonNull String sku,
        @NonNull String costingMethod,
        @Nullable BigDecimal previousUnitCost,
        @NonNull BigDecimal newUnitCost,
        @NonNull BigDecimal onHandQuantity,
        @NonNull BigDecimal totalValueDelta,
        @NonNull String reason,
        @NonNull String actor,
        @NonNull Instant occurredAt) {

    public static final String EVENT_TYPE = "inventory.product-value.changed";
    public static final int SCHEMA_VERSION = 1;

    public ProductValueChangedV1 {
        if (revaluationId == null) {
            throw new IllegalArgumentException("revaluationId must not be null");
        }
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (costingMethod == null || costingMethod.isBlank()) {
            throw new IllegalArgumentException("costingMethod must not be blank");
        }
        if (newUnitCost == null) {
            throw new IllegalArgumentException("newUnitCost must not be null");
        }
        if (totalValueDelta == null) {
            throw new IllegalArgumentException("totalValueDelta must not be null");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor must not be blank");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt must not be null");
        }
    }
}
