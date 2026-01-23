package com.positivity.workorder.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Immutable audit record for all change request resolution decisions.
 * This entity serves as the primary audit trail for customer-facing 
 * approval decisions and emergency overrides.
 */
@Entity
@Table(name = "approval_record", indexes = {
    @Index(name = "idx_approval_change_request", columnList = "changeRequestId"),
    @Index(name = "idx_approval_workorder", columnList = "workorderId")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApprovalRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long changeRequestId;

    @Column(nullable = false)
    private Long workorderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ResolutionStatus resolutionStatus;

    @Column(nullable = false)
    private LocalDateTime resolvedAt;

    @Column(nullable = false)
    private Long resolvedBy;

    @Column(columnDefinition = "TEXT")
    private String exceptionReason;

    @Column(columnDefinition = "TEXT")
    private String approvalNote;

    public enum ResolutionStatus {
        APPROVED,
        REJECTED,
        APPROVED_WITH_EXCEPTION
    }

    @PrePersist
    protected void onCreate() {
        if (resolvedAt == null) {
            resolvedAt = LocalDateTime.now();
        }
    }
}
