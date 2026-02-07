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
@Table(name = "audit_events")
public class AuditEvent {
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
    private String entityType;

    @Column(nullable = false)
    private UUID entityId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private Instant eventTimestamp;

    @Column(nullable = false)
    private UUID userId;

    @Column(columnDefinition = "TEXT")
    private String details;

    @PrePersist
    protected void onCreate() {
        if (eventTimestamp == null) {
            eventTimestamp = Instant.now();
        }
    }
}
