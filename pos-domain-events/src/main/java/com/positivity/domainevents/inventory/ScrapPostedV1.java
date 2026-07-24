package com.positivity.domainevents.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a scrap/write-off was posted to the inventory ledger (odoo-parity D1, issue #1030).
 *
 * <p>Published by pos-inventory on {@code inventory.events.v1} with
 * {@code eventType = "inventory.scrap.posted"} — one fact per posted scrap document (this is an
 * occurrence, not a snapshot: it is never re-emitted). pos-accounting consumes it for shrinkage
 * GL posting (odoo-parity D2).
 *
 * <p><b>Cost semantics — schema v2 (odoo-parity J3, issue #1053):</b> {@code unitCost} is now the
 * J1 costing engine's method-derived unit cost stamped on the {@code SCRAP_OUT} ledger entry at
 * the moment of the movement (ADR-0048), not the interim latest-receipt snapshot. {@code
 * costSource} labels its origin — the resolved costing method ({@code "AVERAGE"} or {@code
 * "STANDARD"}) when the engine costed the movement, or {@code "NONE"} when the SKU was uncosted
 * ({@code unitCost} is then {@code null}). Schema v1 emitted the interim latest-receipt snapshot
 * ({@code costSource = "LATEST_RECEIPT"}); the field shape is unchanged, so v1 and v2 events
 * deserialize identically and consumers read {@code unitCost}/{@code costSource} without
 * branching on {@code schemaVersion}. An uncosted scrap remains a null-cost, record-and-skip
 * fact for the accounting shrinkage consumer (odoo-parity D2).
 *
 * @param scrapId scrap document identifier (aggregate id of the fact)
 * @param sku stock-item identifier that was scrapped (ledger stock-item string)
 * @param locationId location the stock was scrapped from
 * @param storageLocationId bin-level storage location, when the scrap was bin-scoped
 * @param quantity quantity written off (positive)
 * @param reasonCode scrap reason taxonomy value (DAMAGED, EXPIRED, LOST, RECALLED,
 *     CONTAMINATED, WARRANTY_DESTROYED, OTHER)
 * @param unitCost engine method-derived unit cost at scrap posting, {@code null} when uncosted
 * @param costSource origin of the cost: {@code "AVERAGE"}/{@code "STANDARD"} (engine method),
 *     {@code "NONE"} (uncosted), or {@code "LATEST_RECEIPT"} on a legacy v1 event
 * @param workorderId workorder the scrapped part belonged to, when linked
 * @param occurredAt when the scrap was posted to the ledger
 */
public record ScrapPostedV1(
        @NonNull UUID scrapId,
        @NonNull String sku,
        @Nullable UUID locationId,
        @Nullable UUID storageLocationId,
        int quantity,
        @NonNull String reasonCode,
        @Nullable BigDecimal unitCost,
        @NonNull String costSource,
        @Nullable UUID workorderId,
        @NonNull Instant occurredAt) {

    public static final String EVENT_TYPE = "inventory.scrap.posted";
    public static final int SCHEMA_VERSION = 2;

    public ScrapPostedV1 {
        if (scrapId == null) {
            throw new IllegalArgumentException("scrapId must not be null");
        }
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        if (costSource == null || costSource.isBlank()) {
            throw new IllegalArgumentException("costSource must not be blank");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt must not be null");
        }
    }
}
