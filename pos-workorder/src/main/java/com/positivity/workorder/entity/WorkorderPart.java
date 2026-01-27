package com.positivity.workorder.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkorderPart {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_order_service_id")
    private WorkorderService workOrderService;

    private Long productEntityId; // Reference to ProductEntity in pos-catalog
    private Long nonInventoryProductEntityId; // Reference to NonInventoryProductEntity in pos-catalog
    private Integer quantity;

    // Flag to indicate this part was declined by customer during estimate approval
    @Builder.Default
    private Boolean declined = false;

    // Status of the work order item
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private WorkorderItemStatus status = WorkorderItemStatus.OPEN;

    // Reference to the change request that added this item
    private Long changeRequestId;

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

    /**
     * Check if this item can be executed (not pending approval unless emergency exception)
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

