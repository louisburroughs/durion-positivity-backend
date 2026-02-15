package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.*;
import lombok.*;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@jakarta.persistence.Table(name = "workorder_part", uniqueConstraints = {
        // CAP:004 Story #27 - prevent the same estimate item from being promoted more
        // than once
        @UniqueConstraint(name = "uq_workorder_part_origin_estimate_item", columnNames = { "origin_estimate_item_id" })
}, check = {
        @CheckConstraint(name = "ck_workorder_part_has_reference", constraint = "work_order_service_id IS NOT NULL OR work_order_id IS NOT NULL")
})
public class WorkorderPart {
    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void generateId() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_order_service_id", nullable = true) // Allow standalone parts not tied to a service
    private WorkorderService workOrderService;

    // CAP:004 Story #27 - Add direct workorder reference for standalone parts
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_order_id", nullable = true)
    private Workorder workorder;

    private UUID productEntityId; // Reference to ProductEntity in pos-catalog
    private UUID nonInventoryProductEntityId; // Reference to NonInventoryProductEntity in pos-catalog

    // CAP:004 Story #27 - Financial snapshot fields from estimate promotion
    @Column(length = 500)
    @Nullable
    private String description; // Snapshotted description from estimate

    @Column(precision = 19, scale = 4)
    @Nullable
    private BigDecimal quantity; // Snapshotted quantity from estimate

    @Column(precision = 19, scale = 2)
    @Nullable
    private BigDecimal unitPrice; // Snapshotted unit price from estimate

    @Column(precision = 19, scale = 2)
    @Nullable
    private BigDecimal lineTotal; // Snapshotted line total = quantity * unitPrice

    @Column(length = 50)
    @Nullable
    private String taxCode; // Snapshotted tax code at promotion time

    @Column(columnDefinition = "UUID")
    @Nullable
    private UUID originEstimateItemId; // Traceability link to source EstimateItem

    // Flag to indicate this part was declined by customer during estimate approval
    @Builder.Default
    private Boolean declined = false;

    // Status of the work order item
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private WorkorderItemStatus status = WorkorderItemStatus.OPEN;

    // Reference to the change request that added this item
    private UUID changeRequestId;

    // Emergency/Safety flags and documentation
    @Builder.Default
    private Boolean isEmergencySafety = false;

    private String photoEvidenceUrl;

    @Column(columnDefinition = "TEXT")
    private String emergencyNotes;

    @Builder.Default
    private Boolean photoNotPossible = false;

    // Customer denial acknowledgment for emergency items
    private Boolean customerDenialAcknowledged;

    // CAP:005 Story #158 - Parts usage tracking fields
    @Column(precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal quantityIssued = BigDecimal.ZERO;

    @Column(precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal quantityConsumed = BigDecimal.ZERO;

    @Column(precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal quantityReturned = BigDecimal.ZERO;

    /**
     * Check if this item can be executed (not pending approval unless emergency
     * exception)
     */
    public boolean canExecute() {
        return status != WorkorderItemStatus.PENDING_APPROVAL
                || (isEmergencySafety && customerDenialAcknowledged != null);
    }

    /**
     * Check if this item can consume inventory
     */
    public boolean canConsumeInventory() {
        return status != WorkorderItemStatus.PENDING_APPROVAL;
    }
}
