package com.positivity.shopmanager.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "hr_integration_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HrIntegrationLog {

    @Id
    @Column(name = "event_id", columnDefinition = "UUID")
    private UUID eventId;

    @Column(name = "person_id")
    private String personId;

    @Column(name = "event_type")
    private String eventType;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "status")
    private String status;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "payload_hash")
    private String payloadHash;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    public void requireEventId() {
        if (eventId == null) {
            throw new IllegalStateException(
                    "HrIntegrationLog.eventId must be set before persisting — eventId is the dedupe key");
        }
    }
}
