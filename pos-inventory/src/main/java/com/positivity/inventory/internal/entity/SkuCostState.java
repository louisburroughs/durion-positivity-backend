package com.positivity.inventory.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Per-SKU running cost state maintained by the costing engine (odoo-parity J1,
 * issue #1048; ADR-0048). One row per {@code stockItemId}; the ledger posting
 * funnel updates it in the same transaction as every on-hand-affecting ledger
 * append.
 *
 * <p>Field semantics by method:
 * <ul>
 *   <li><b>{@code avgCost}</b> — under AVERAGE, the running weighted-average
 *       unit cost (Odoo AVCO). Under STANDARD it doubles as the "latest receipt
 *       cost" memo used as the fallback when no {@code standardCost} is
 *       configured. Scale 6 to preserve precision across incremental recomputes;
 *       the value stamped onto a ledger entry is rounded to the ledger's scale
 *       4.</li>
 *   <li><b>{@code onHandQty}</b> — running quantity the engine has costed so
 *       far (drives the AVCO denominator and the negative-branch decision). Kept
 *       independently of {@code inventory_stock_summary} so costing is
 *       self-contained; J2 reconciles the value read model.</li>
 *   <li><b>{@code standardCost}</b> — the SKU's configured standard price
 *       (STANDARD method). Null until set via the revaluation workflow (J4);
 *       receipts never change it in J1.</li>
 * </ul>
 *
 * <p>This entity holds no value columns on {@code inventory_stock_summary} — J1
 * deliberately does not touch the summary read model (that is J2).
 */
@Entity
@Table(
        name = "sku_cost_state",
        indexes = @Index(name = "uq_sku_cost_state_stock_item", columnList = "stock_item_id", unique = true))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class SkuCostState {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "cost_state_id", updatable = false, nullable = false)
    private UUID costStateId;

    /** Natural unique key: the SKU this running cost state belongs to (one row per stock item). */
    @Column(name = "stock_item_id", nullable = false, updatable = false, length = 255, unique = true)
    private String stockItemId;

    /** Running weighted-average cost (AVERAGE) or latest-receipt-cost memo (STANDARD); null when unseeded. */
    @Column(name = "avg_cost", precision = 19, scale = 6)
    private BigDecimal avgCost;

    /** Quantity the engine has costed so far (AVCO denominator + negative-branch input). */
    @Column(name = "on_hand_qty", nullable = false, precision = 19, scale = 4)
    private BigDecimal onHandQty;

    /** Configured standard price (STANDARD method); null until set via revaluation (J4). */
    @Column(name = "standard_cost", precision = 19, scale = 6)
    private BigDecimal standardCost;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
