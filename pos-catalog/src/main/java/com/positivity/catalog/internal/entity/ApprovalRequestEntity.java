package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@Setter
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "approval_request")
public class ApprovalRequestEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID overrideId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ApprovalRequestStatus status;

    @Column(nullable = false, columnDefinition = "UUID")
    private UUID assignedApproverId;

    @Column(nullable = false, length = 128)
    private String assignmentStrategy;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant resolvedAt;

    @PrePersist
    public void prePersist() {
        // status defaults are kept as business behavior
        if (status == null) {
            status = ApprovalRequestStatus.PENDING;
        }
    }
}
