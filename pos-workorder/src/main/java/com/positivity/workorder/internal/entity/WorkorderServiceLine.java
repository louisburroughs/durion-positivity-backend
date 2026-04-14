package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.workorder.internal.enums.WorkorderItemStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
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
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "workorder_service")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class WorkorderServiceLine {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_order_id")
    private Workorder workOrder;

    private UUID serviceEntityId; // Reference to ServiceEntity in pos-catalog
    private UUID technicianId; // Reference to Technician

    // CAP:004 Story #27 - Financial snapshot fields from estimate promotion
    @Column(length = 500)
    @Nullable
    private String description; // Snapshotted description from estimate

    @Column(precision = 19, scale = 4)
    @Nullable
    private BigDecimal quantity; // Snapshotted quantity (hours for labor)

    @Column(precision = 19, scale = 2)
    @Nullable
    private BigDecimal unitPrice; // Snapshotted unit price (hourly rate for labor)

    @Column(precision = 19, scale = 2)
    @Nullable
    private BigDecimal lineTotal; // Snapshotted line total = quantity * unitPrice

    @Column(length = 50)
    @Nullable
    private String taxCode; // Snapshotted tax code at promotion time

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "origin_estimate_item_id")
    @ToString.Exclude
    @Nullable
    private EstimateItem originEstimateItem; // Traceability link to source EstimateItem

    // Flag to indicate this service was declined by customer during estimate
    // approval
    @Builder.Default
    private Boolean declined = false;

    // Status of the work order item
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private WorkorderItemStatus status = WorkorderItemStatus.OPEN;

    // Reference to the change request that added this item
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "change_request_id")
    @ToString.Exclude
    private ChangeRequest changeRequest;

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

    @jakarta.persistence.Transient
    public UUID getOriginEstimateItemId() {
        return originEstimateItem != null ? originEstimateItem.getId() : null;
    }

    @jakarta.persistence.Transient
    public void setOriginEstimateItemId(UUID originEstimateItemId) {
        this.originEstimateItem = originEstimateItemId != null ? new EstimateItem(originEstimateItemId) : null;
    }

    @jakarta.persistence.Transient
    public UUID getChangeRequestId() {
        return changeRequest != null ? changeRequest.getId() : null;
    }

    @jakarta.persistence.Transient
    public void setChangeRequestId(UUID changeRequestId) {
        this.changeRequest = changeRequestId != null ? new ChangeRequest(changeRequestId) : null;
    }

    public WorkorderServiceLine(UUID id) {
        this.id = id;
    }
}
