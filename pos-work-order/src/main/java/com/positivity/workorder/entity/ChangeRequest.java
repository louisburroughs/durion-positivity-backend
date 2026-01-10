package com.positivity.workorder.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChangeRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long workOrderId;

    @Column(nullable = false)
    private Long requestedByUserId;

    @Column(nullable = false)
    private LocalDateTime requestedAt;

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

    private Long supplementalEstimatePdfId;

    private LocalDateTime approvedAt;
    private Long approvedBy;

    private LocalDateTime declinedAt;

    public enum ChangeRequestStatus {
        AWAITING_ADVISOR_REVIEW,
        APPROVED,
        DECLINED,
        CANCELLED
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
    protected void onCreate() {
        if (requestedAt == null) {
            requestedAt = LocalDateTime.now();
        }
        if (status == null) {
            status = ChangeRequestStatus.AWAITING_ADVISOR_REVIEW;
        }
    }
}
