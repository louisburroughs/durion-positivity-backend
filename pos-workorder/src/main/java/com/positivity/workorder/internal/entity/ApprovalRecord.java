package com.positivity.workorder.internal.entity;

import java.time.Clock;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
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
        @Index(name = "idx_approval_change_request", columnList = "changeRequestId"),
        @Index(name = "idx_approval_workorder", columnList = "workorderId")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class ApprovalRecord {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false)
    private UUID changeRequestId;

    @Column(nullable = false)
    private UUID workorderId;

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

    @PrePersist
    protected void prePersist() {
        if (resolvedAt == null) {
            resolvedAt = LocalDateTime.now(Clock.systemUTC());
        }
}
}
