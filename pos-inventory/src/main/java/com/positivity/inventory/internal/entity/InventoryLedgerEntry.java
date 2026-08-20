package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Immutable ledger entry representing a single inventory transaction.
 */
@Entity
@Table(
        name = "inventory_ledger_entry",
        indexes = {@Index(name = "idx_inventory_ledger_entry_location_event", columnList = "location_id, event_type")})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class InventoryLedgerEntry {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID ledgerEntryId;

    @Column(nullable = false)
    private String stockItemId;

    private UUID adjustmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InventoryLedgerEventType eventType;

    /**
     * Signed quantity delta this entry posts. Decimal-capable (ADR-0055, #1414): a product whose
     * catalog {@code product_uom} BASE row declares {@code precision_scale > 0} posts fractions
     * to that scale, while a product declaring 0 — every product until one is seeded otherwise —
     * is still refused a fraction on both the demand and the supply side. The column width is
     * what a divisible product needs; the declaration is what decides who may use it.
     *
     * <p>Compare with {@code compareTo}, never {@code equals}: {@code 1} and {@code 1.0} are the
     * same quantity and are not equal.
     */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal changeInQuantity;

    /** Running on-hand after this entry, on the same decimal basis as {@link #changeInQuantity}. */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantityAfter;

    @Column(precision = 19, scale = 4)
    private BigDecimal unitCost;

    @Column(nullable = false)
    private String transactionUserId;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant timestamp;

    @Column
    private UUID locationId;

    @Column
    private UUID fromLocationId;

    @Column
    private UUID toLocationId;

    /**
     * Lot the quantity belongs to (odoo-parity E1, issue #1038): stamped by receipt postings for
     * LOT-tracked products; null for untracked products and, until E2 wires the outbound flows,
     * for all non-receipt event types.
     */
    @Column(name = "lot_id")
    private UUID lotId;

    @Column(length = 100)
    private String reasonCode;

    @Column(length = 255)
    private String sourceTransactionId;

    @Column(length = 50)
    private String unitOfMeasure;

    @Column(length = 2000)
    private String notes;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Transient carrier for the serial numbers this on-hand-affecting entry enumerates (inbound)
     * or consumes (outbound) for a SERIAL-tracked stock item (odoo-parity E4, issue #1050). Never
     * persisted — the ledger stays quantity-only, and the posting funnel
     * ({@code SerialUnitPostingService}) turns these into {@code InventorySerialUnit} row
     * transitions in the same transaction. Empty/null for untracked and LOT-tracked stock items.
     */
    @Transient
    @Builder.Default
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private List<String> serialNumbers = List.of();

    /**
     * Transient carrier for the workorder a serialized outbound posting consumes stock to (issue
     * #1050): recorded on each consumed {@code InventorySerialUnit} so warranty's
     * {@code WarrantyPartReturnHold.serialNumber} joins to a stock record. Never persisted.
     */
    @Transient
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private String serialWorkorderId;
}
