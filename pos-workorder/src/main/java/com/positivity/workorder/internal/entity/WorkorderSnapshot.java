package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
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

    @PrePersist
    public void generateId() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
    }

    @Column(nullable = false)
    private UUID workorderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkorderStatus status;

    @Column(nullable = false)
    private Instant capturedAt;

    @Column(nullable = false)
    private UUID capturedBy;

    @Column(nullable = false)
    private String snapshotType;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String snapshotData;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @PrePersist
    protected void onCreate() {
        if (capturedAt == null) {
            capturedAt = Instant.now();
        }
    }
}
