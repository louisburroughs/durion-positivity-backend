package com.positivity.securityservice.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Immutable audit event record for price/cost and rule evaluation changes.
 *
 * Issue: #41
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "audit_log_events", indexes = {
        @Index(name = "idx_audit_log_events_timestamp", columnList = "timestamp"),
        @Index(name = "idx_audit_log_events_actor_id", columnList = "actor_id"),
        @Index(name = "idx_audit_log_events_entity_id", columnList = "entity_id")
})
public class AuditLogEvent {

    @Id
    @Column(name = "event_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "timestamp", nullable = false, updatable = false)
    private Instant timestamp;

    @Column(name = "event_type", nullable = false, length = 100, updatable = false)
    private String eventType;

    @Column(name = "actor_id", nullable = false, length = 255, updatable = false)
    private String actorId;

    @Column(name = "entity_id", nullable = false, length = 255, updatable = false)
    private String entityId;

    @Column(name = "entity_type", nullable = false, length = 255, updatable = false)
    private String entityType;

    @Column(name = "old_value", columnDefinition = "TEXT", nullable = false, updatable = false)
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT", nullable = false, updatable = false)
    private String newValue;

    @Column(name = "context", columnDefinition = "TEXT", updatable = false)
    private String context;

    @PrePersist
    protected void onCreate() {
        if (eventId == null) {
            eventId = UUIDv7Generator.generate();
        }
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }
}
