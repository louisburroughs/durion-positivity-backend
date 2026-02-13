package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "work_order_snapshots")
public class WorkorderSnapshot {
    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false)
    private UUID workorderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkorderStatus status;

    @Column(nullable = false)
    private Instant capturedAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column(nullable = false)
    private UUID capturedBy;

    @Column(nullable = false)
    private String snapshotType;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String snapshotData;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @PrePersist
    protected void prePersist() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
        if (capturedAt == null) {
            capturedAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
