package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class Workorder {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private UUID shopId; // Reference to Shop
    private UUID vehicleId; // Reference to Vehicle
    private UUID customerId; // Reference to Customer
    private UUID approvalId; // Reference to CustomerApproval (from pos-customer-approval)
    private UUID estimateId; // Reference to Estimate - work order created from approved estimate

    @Column(name = "invoice_id", columnDefinition = "UUID")
    private UUID invoiceId; // Reference to generated invoice for reverse lookup traceability

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

    // Controlled reopen fields (CAP:006)
    @Builder.Default
    private Boolean isReopened = false;

    private Instant reopenedAt;
    private UUID reopenedBy;

    @Column(columnDefinition = "TEXT")
    private String reopenReason;

    /**
     * Check if the work order is locked from modifications.
     * A work order is locked if it's in COMPLETED or CANCELLED status.
     */
    public boolean isLocked() {
        return status == WorkorderStatus.CANCELLED
                || (status == WorkorderStatus.COMPLETED && !Boolean.TRUE.equals(isReopened));
    }
}
