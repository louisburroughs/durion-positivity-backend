package com.positivity.workorder.internal.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Workorder {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID shopId; // Reference to Shop
    private UUID vehicleId; // Reference to Vehicle
    private UUID customerId; // Reference to Customer
    private UUID approvalId; // Reference to CustomerApproval (from pos-customer-approval)
    private UUID estimateId; // Reference to Estimate - work order created from approved estimate
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private WorkorderStatus status = WorkorderStatus.DRAFT;

    @OneToMany(mappedBy = "workOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WorkorderService> services;

    // Approval-related fields
    private Instant approvedAt;
    private UUID approvedBy;

    // Customer signature capture for approval
    @Column(length = 100000)
    private String signatureData; // Base64-encoded signature image
    private String signatureMimeType; // MIME type (e.g., image/png)
    private String signerName; // Name of person who signed
    private String approvalNotes; // Additional notes at approval time

    // Completion-related fields
    private Instant completedAt;
    private UUID completedBy;

    @Column(columnDefinition = "TEXT")
    private String completionNotes;

    /**
     * Check if the work order is locked from modifications.
     * A work order is locked if it's in COMPLETED or CANCELLED status.
     */
    public boolean isLocked() {
        return status == WorkorderStatus.COMPLETED || status == WorkorderStatus.CANCELLED;
    }
}
