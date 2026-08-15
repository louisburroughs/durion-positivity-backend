package com.positivity.inventory.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One line of a projected purchase order.
 *
 * <p>Deliberately not a JPA association to {@link ExtPurchaseOrderReplica}'s owning side beyond the
 * plain column: a projection is not an aggregate and modelling it as one invites code to navigate it
 * as though this module owned the order.
 */
@Entity
@Table(
        name = "ext_purchase_order_line",
        indexes = {
            @Index(name = "idx_ext_po_line_sku", columnList = "sku_id"),
            @Index(name = "idx_ext_po_line_order", columnList = "purchase_order_id")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExtPurchaseOrderLineReplica {

    @Id
    @Column(name = "line_id", nullable = false)
    private UUID lineId;

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    /**
     * What was ordered. Does not move as goods arrive.
     *
     * <p>Precision and scale mirror the source column ({@code purchase_order_line.quantity_decimal},
     * {@code numeric(18,6)}) so projecting a fractional quantity never rounds it.
     */
    @Column(name = "ordered_quantity", nullable = false, precision = 18, scale = 6)
    private BigDecimal orderedQuantity;

    /**
     * What is still outstanding.
     *
     * <p>This is the figure availability-to-promise sums. Stock already in the building is counted by
     * the ledger, so adding the ordered quantity instead would count received goods twice.
     */
    @Column(name = "open_quantity", nullable = false, precision = 18, scale = 6)
    private BigDecimal openQuantity;

    @Column(name = "unit_cost_minor")
    private Long unitCostMinor;

    @Column(name = "description")
    private String description;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): the primary key IS a
     * UUIDv7 minted by the owning module; this replica stores it verbatim. Minting one locally
     * would break the correspondence with the order the owner published.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
