package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.InventorySerialStatus;
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
import jakarta.persistence.UniqueConstraint;
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
 * Serial unit master record (odoo-parity E4, issue #1050; spec §6): one row per physical
 * serialized unit of a SERIAL-tracked stock item, unique per (stockItemId, serialNumber).
 *
 * <p>Rows are created lazily by the inbound receipt posting when a SERIAL-tracked product is
 * received (serial enumeration = quantity invariant: a receipt of N units enumerates N serials),
 * and their {@code status}/{@code locationId} are transitioned by the ledger posting funnel as
 * matching outbound/inbound entries name them. {@code stockItemId} is the ledger stock-item
 * string (a catalog product UUID for tracked products — only replica-known products can be
 * SERIAL-tracked).
 *
 * <p>{@code workorderId} is the consumption linkage that finally joins warranty's
 * {@code WarrantyPartReturnHold.serialNumber} to a stock record (issue #1050): a serial consumed
 * to a workorder carries the workorder id and the consuming ledger entry.
 */
@Entity
@Table(
        name = "inventory_serial_unit",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_inventory_serial_unit_sku_serial",
                        columnNames = {"stock_item_id", "serial_number"}),
        indexes = {
            @Index(name = "idx_inventory_serial_unit_stock_item", columnList = "stock_item_id"),
            @Index(name = "idx_inventory_serial_unit_on_hand", columnList = "stock_item_id, location_id, status"),
            @Index(name = "idx_inventory_serial_unit_workorder", columnList = "workorder_id")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class InventorySerialUnit {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID serialUnitId;

    /** Ledger stock-item string; a catalog product UUID for SERIAL-tracked products. */
    @Column(name = "stock_item_id", nullable = false)
    private String stockItemId;

    /** Manufacturer/vendor serial number; unique per stock item. */
    @Column(name = "serial_number", nullable = false, length = 128)
    private String serialNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private InventorySerialStatus status = InventorySerialStatus.IN_STOCK;

    /** Current location while {@code IN_STOCK}; null once the unit has left stock. */
    @Column(name = "location_id")
    private UUID locationId;

    /** Optional lot linkage — a SERIAL unit may also belong to a lot (issue #1050, lot linkage optional). */
    @Column(name = "lot_id")
    private UUID lotId;

    /** The receipt ledger entry that enumerated (or last re-stocked) this unit. */
    @Column(name = "receipt_ledger_entry_id")
    private UUID receiptLedgerEntryId;

    /** The outbound ledger entry that last consumed this unit (issue/scrap). */
    @Column(name = "consumption_ledger_entry_id")
    private UUID consumptionLedgerEntryId;

    /** Workorder the unit was consumed to, when the consuming posting carried one. */
    @Column(name = "workorder_id", length = 255)
    private String workorderId;

    /** First (or most recent re-stock) receipt of this unit into stock. */
    @Column(name = "received_at")
    private Instant receivedAt;

    /** When the unit last left stock (issue/scrap). */
    @Column(name = "consumed_at")
    private Instant consumedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
