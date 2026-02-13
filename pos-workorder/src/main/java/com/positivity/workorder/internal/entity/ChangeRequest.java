package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChangeRequest {
    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false)
    private UUID workorderId;

    @Column(nullable = false)
    private UUID requestedByUserId;

    @Column(nullable = false)
    private LocalDateTime requestedAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

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
    private UUID approvedBy;

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
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
        if (requestedAt == null) {
            requestedAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (status == null) {
            status = ChangeRequestStatus.AWAITING_ADVISOR_REVIEW;
        }
    }

    @PreUpdate
    protected void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
