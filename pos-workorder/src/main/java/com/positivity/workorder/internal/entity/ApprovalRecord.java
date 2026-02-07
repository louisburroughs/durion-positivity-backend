package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

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
    @Column(columnDefinition = "UUID")
    private UUID id;

    @PrePersist
    public void generateId() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
    }

    @Column(nullable = false)
    private UUID changeRequestId;

    @Column(nullable = false)
    private UUID workorderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ResolutionStatus resolutionStatus;

    @Column(nullable = false)
    private LocalDateTime resolvedAt;

    @Column(nullable = false)
    private UUID resolvedBy;

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
