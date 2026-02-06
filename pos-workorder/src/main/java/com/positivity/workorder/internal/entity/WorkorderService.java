package com.positivity.workorder.internal.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.List;
import java.util.UUID;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkorderService {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_order_id")
    private Workorder workOrder;

    private UUID serviceEntityId; // Reference to ServiceEntity in pos-catalog
    private UUID technicianId; // Reference to Technician

    // Flag to indicate this service was declined by customer during estimate
    // approval
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

    @OneToMany(mappedBy = "workOrderService", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WorkorderPart> parts;

    /**
     * Check if this service can be executed (not pending approval unless emergency
     * exception)
     */
    public boolean canExecute() {
        return status != WorkorderItemStatus.PENDING_APPROVAL
                || (isEmergencySafety && customerDenialAcknowledged != null);
    }
}
