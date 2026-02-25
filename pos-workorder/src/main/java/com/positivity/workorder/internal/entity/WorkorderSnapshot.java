package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.workorder.internal.enums.WorkorderStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "work_order_snapshots")
@EntityListeners(AuditingEntityListener.class)
public class WorkorderSnapshot {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false)
    private UUID workorderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkorderStatus status;

    @Column(nullable = false)
    private Instant capturedAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private String capturedBy;

    @Column(nullable = false)
    private String snapshotType;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String snapshotData;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @PrePersist
    protected void prePersist() {
        if (capturedAt == null) {
            capturedAt = Instant.now();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }
}
