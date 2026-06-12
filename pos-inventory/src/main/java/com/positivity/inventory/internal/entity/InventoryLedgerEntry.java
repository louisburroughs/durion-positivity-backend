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

    @Column(nullable = false)
    private Integer changeInQuantity;

    @Column(nullable = false)
    private Integer quantityAfter;

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
}
