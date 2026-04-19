package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.time.TimeSource;
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
import jakarta.persistence.PrePersist;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class ChangeRequest {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workorder_id", nullable = false)
    private Workorder workorder;

    @Column(nullable = false)
    private String requestedByUserId;

    @Column(nullable = false)
    private LocalDateTime requestedAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ChangeRequestStatus status;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isEmergencyException = false;

    @Column(columnDefinition = "TEXT")
    private String exceptionReason;

    @Column(columnDefinition = "TEXT")
    private String approvalNote;

    @Builder.Default
    @Column(nullable = false)
    private Boolean isApprovalGated = true;

    private Long supplementalEstimatePdfId;

    private LocalDateTime approvedAt;
    private String approvedBy;

    private LocalDateTime declinedAt;

    public enum ChangeRequestStatus {
        AWAITING_ADVISOR_REVIEW,
        APPROVED,
        DECLINED,
        CANCELLED,
        APPROVED_WITH_EXCEPTION
    }

    /**
     * Determines if this change request can be approved
     */
    public boolean canApprove() {
        return status == ChangeRequestStatus.AWAITING_ADVISOR_REVIEW;
    }

    /**
     * Determines if this change request can be declined
     */
    public boolean canDecline() {
        return status == ChangeRequestStatus.AWAITING_ADVISOR_REVIEW;
    }

    /**
     * Determines if this change request can be cancelled
     */
    public boolean canCancel() {
        return status == ChangeRequestStatus.AWAITING_ADVISOR_REVIEW;
    }

    @PrePersist
    protected void prePersist() {
        if (requestedAt == null) {
            requestedAt = TimeSource.localDateTime();
        }
        if (status == null) {
            status = ChangeRequestStatus.AWAITING_ADVISOR_REVIEW;
        }
    }

    public ChangeRequest(UUID id) {
        this.id = id;
    }
}
