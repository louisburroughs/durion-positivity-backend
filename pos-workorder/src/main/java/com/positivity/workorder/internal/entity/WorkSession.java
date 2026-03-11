package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.workorder.internal.enums.WorkSessionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity for a mechanic work session associated with a work order task.
 * Implements CAP-139 Story #68.
 */
@Entity
@Table(name = "work_session", indexes = {
        @Index(name = "idx_work_session_mechanic_id", columnList = "mechanicId"),
        @Index(name = "idx_work_session_work_order_id", columnList = "work_order_id"),
        @Index(name = "idx_work_session_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class WorkSession {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID workSessionId;

    @NonNull
    @Column(nullable = false, columnDefinition = "UUID")
    private UUID mechanicId;

    @NonNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "work_order_id", nullable = false)
    @ToString.Exclude
    private Workorder workOrder;

    @jakarta.persistence.Transient
    public UUID getWorkOrderId() {
        return workOrder != null ? workOrder.getId() : null;
    }

    @jakarta.persistence.Transient
    public void setWorkOrderId(UUID workOrderId) {
        this.workOrder = new Workorder(workOrderId);
    }

    @NonNull
    @Column(nullable = false, columnDefinition = "UUID")
    private UUID workOrderTaskId;

    @NonNull
    @Column(nullable = false, columnDefinition = "UUID")
    private UUID locationId;

    @Nullable
    @Column(columnDefinition = "UUID")
    private UUID resourceId;

    @NonNull
    @Column(nullable = false)
    private Instant startAt;

    @Nullable
    @Column
    private Instant endAt;

    @NonNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkSessionStatus status;

    @Column(nullable = false)
    private boolean locked;

    @Column(nullable = false)
    private int totalDurationSeconds;

    @Nullable
    @Column
    private Instant approvedAt;

    @Nullable
    @Column
    private String approvedByUserId;

    @Nullable
    @Column(columnDefinition = "TEXT")
    private String approvalNotes;

    @Nullable
    @Column
    private Instant lockedAt;

    @Column(nullable = false)
    private boolean overlapOverrideUsed;

    @Nullable
    @Column(columnDefinition = "TEXT")
    private String overrideReason;

    @Nullable
    @Column
    private String overriddenByUserId;

    @Nullable
    @Column
    private Instant overrideAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
