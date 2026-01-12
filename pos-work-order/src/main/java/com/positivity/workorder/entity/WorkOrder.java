package com.positivity.workorder.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.List;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shopId; // Reference to Shop
    private Long vehicleId; // Reference to Vehicle
    private Long customerId; // Reference to Customer
    private Long approvalId; // Reference to CustomerApproval (from pos-customer-approval)
    private Long estimateId; // Reference to Estimate - work order created from approved estimate

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private WorkOrderStatus status = WorkOrderStatus.DRAFT;

    @OneToMany(mappedBy = "workOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WorkOrderService> services;

    // Completion-related fields
    private Instant completedAt;
    private Long completedBy;
    
    @Column(columnDefinition = "TEXT")
    private String completionNotes;

    /**
     * Check if the work order is locked from modifications.
     * A work order is locked if it's in COMPLETED or CANCELLED status.
     */
    public boolean isLocked() {
        return status == WorkOrderStatus.COMPLETED || status == WorkOrderStatus.CANCELLED;
    }
}

