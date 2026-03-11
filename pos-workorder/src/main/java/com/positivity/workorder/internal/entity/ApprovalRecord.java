package com.positivity.workorder.internal.entity;

import java.time.Clock;

import com.positivity.shared.id.UUIDv7Id;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Immutable audit record for all change request resolution decisions.
 * This entity serves as the primary audit trail for customer-facing
 * approval decisions and emergency overrides.
 */
@Entity
@Table(name = "approval_record", indexes = {
        @Index(name = "idx_approval_change_request", columnList = "change_request_id"),
        @Index(name = "idx_approval_workorder", columnList = "workorder_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = { "changeRequest", "workorder" })
@EntityListeners(AuditingEntityListener.class)
public class ApprovalRecord {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "change_request_id", nullable = false)
    private ChangeRequest changeRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workorder_id", nullable = false)
    private Workorder workorder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ResolutionStatus resolutionStatus;

    @Column(nullable = false)
    private LocalDateTime resolvedAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private String resolvedBy;

    @Column(columnDefinition = "TEXT")
    private String exceptionReason;

    @Column(columnDefinition = "TEXT")
    private String approvalNote;

    public enum ResolutionStatus {
        APPROVED,
        REJECTED,
        APPROVED_WITH_EXCEPTION
    }

    @Transient
    public UUID getChangeRequestId() {
        return changeRequest != null ? changeRequest.getId() : null;
    }

    public void setChangeRequestId(UUID changeRequestId) {
        this.changeRequest = changeRequestId != null ? new ChangeRequest(changeRequestId) : null;
    }

    @Transient
    public UUID getWorkorderId() {
        return workorder != null ? workorder.getId() : null;
    }

    public void setWorkorderId(UUID workorderId) {
        this.workorder = workorderId != null ? new Workorder(workorderId) : null;
    }

    @PrePersist
    protected void prePersist() {
        if (resolvedAt == null) {
            resolvedAt = LocalDateTime.now(Clock.systemUTC());
        }
    }
}
