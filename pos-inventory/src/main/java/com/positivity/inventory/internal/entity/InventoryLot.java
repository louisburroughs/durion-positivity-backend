package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.InventoryLotStatus;
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
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Lot master record (odoo-parity E1, issue #1038; spec §6): one row per
 * (stockItemId, lotNumber), created lazily by the inbound receipt paths when a
 * LOT-tracked product is received.
 *
 * <p>{@code stockItemId} is the ledger stock-item string (a catalog product UUID for
 * tracked products — only replica-known products can be LOT-tracked). {@code receivedAt}
 * is stamped on first receipt; {@code vendorId} comes from the receiving document where
 * available. {@code expirationDate} stays null in E1 — the E3 expiry flows populate it via
 * the {@code LotExpiryProvider} SPI.
 */
@Entity
@Table(
        name = "inventory_lot",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_inventory_lot_sku_lot_number",
                        columnNames = {"stock_item_id", "lot_number"}),
        indexes = @Index(name = "idx_inventory_lot_stock_item", columnList = "stock_item_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class InventoryLot {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID lotId;

    /** Ledger stock-item string; a catalog product UUID for LOT-tracked products. */
    @Column(name = "stock_item_id", nullable = false)
    private String stockItemId;

    /** Vendor/manufacturer lot or batch number; unique per stock item. */
    @Column(name = "lot_number", nullable = false, length = 128)
    private String lotNumber;

    /** First receipt of this lot into stock. */
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    /** Vendor the lot was first received from, when the document carries one. */
    @Column(name = "vendor_id")
    private UUID vendorId;

    /** Expiration date; null in E1 — populated by the E3 expiry flows. */
    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private InventoryLotStatus status = InventoryLotStatus.ACTIVE;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
