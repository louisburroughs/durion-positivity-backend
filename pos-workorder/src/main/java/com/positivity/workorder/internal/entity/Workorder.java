package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

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
    private Instant updatedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private UUID shopId; // Reference to Shop
    private UUID vehicleId; // Reference to Vehicle
    private UUID customerId; // Reference to Customer
    private UUID approvalId; // Reference to CustomerApproval (from pos-customer-approval)

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "estimate_id")
    @ToString.Exclude
    private Estimate estimate; // Reference to Estimate - work order created from approved estimate

    @Transient
    public UUID getEstimateId() {
        return estimate != null ? estimate.getId() : null;
    }

    @Transient
    public void setEstimateId(UUID estimateId) {
        this.estimate = estimateId != null ? new Estimate(estimateId) : null;
    }

    @Column(name = "invoice_id", columnDefinition = "UUID")
    private UUID invoiceId; // Reference to generated invoice for reverse lookup traceability

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private WorkorderStatus status = WorkorderStatus.DRAFT;

    @OneToMany(mappedBy = "workOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WorkorderServiceLine> services;

    // Approval-related fields
    private Instant approvedAt;
    // private String approvedBy;

    // Customer signature capture for approval
    @Column(length = 100000)
    private String signatureData; // Base64-encoded signature image

    private String signatureMimeType; // MIME type (e.g., image/png)
    private String signerName; // Name of person who signed
    private String approvalNotes; // Additional notes at approval time

    // Completion-related fields
    private Instant completedAt;
    private String completedBy;

    @Column(columnDefinition = "TEXT")
    private String completionNotes;

    // Controlled reopen fields (CAP:006)
    @Builder.Default
    private Boolean isReopened = false;

    // Assignment context fields (CAP:140 Story #64)
    private UUID locationId;
    private UUID resourceId;

    @Column(name = "scheduled_date", nullable = true)
    private LocalDate scheduledDate;

    @Column(columnDefinition = "TEXT")
    private String mechanicIds; // JSON array of mechanic UUIDs e.g. ["uuid1","uuid2"]

    @Column(columnDefinition = "TEXT")
    private String requiredCertifications; // JSON array of certification codes e.g. ["BRAKE_CERT","ALIGNMENT_CERT"]

    // Operational context fields (CAP:140 Story #59)
    private String operationalContextVersion;
    private Instant workStartedAt;

    private Instant reopenedAt;
    private String reopenedBy;

    @Column(columnDefinition = "TEXT")
    private String reopenReason;

    // CRM reference IDs — immutable point-in-time snapshot (CAP-094 Story #93)
    @Column(length = 36, updatable = false)
    private String crmPartyId;

    @Column(length = 36, updatable = false)
    private String crmVehicleId;

    @Builder.Default
    @Column(columnDefinition = "TEXT", updatable = false)
    @Convert(converter = StringListJsonConverter.class)
    private List<String> crmContactIds = new ArrayList<>();

    /**
     * Check if the work order is locked from modifications.
     * A work order is locked if it's in COMPLETED or CANCELLED status.
     */
    public boolean isLocked() {
        return status == WorkorderStatus.CANCELLED
                || (status == WorkorderStatus.COMPLETED && !Boolean.TRUE.equals(isReopened));
    }

    public Workorder(UUID id) {
        this.id = id;
    }
}
