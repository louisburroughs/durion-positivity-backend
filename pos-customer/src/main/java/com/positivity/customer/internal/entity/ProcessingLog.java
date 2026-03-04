package com.positivity.customer.internal.entity;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.positivity.customer.internal.enums.ProcessingStatus;
import com.positivity.shared.id.UUIDv7Id;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Audit log for inbound workorder-originated events consumed by the CRM system.
 *
 * <p>Each inbound event produces exactly one {@code ProcessingLog} entry recording
 * the event's identity, type, final processing status, and any failure details.
 * The {@code eventId} column carries a unique index to support fast idempotency checks.</p>
 *
 * @see ProcessingStatus
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "processing_log", indexes = {
        @Index(name = "idx_proc_log_event_id", columnList = "event_id", unique = true)
})
@EntityListeners(AuditingEntityListener.class)
public class ProcessingLog {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "event_id", unique = true, nullable = false, length = 36, updatable = false)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /** Workorder ID (or other correlation reference) copied from the event envelope for traceability. */
    @Column(name = "correlation_id", nullable = true, length = 36)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private ProcessingStatus status;

    @Column(name = "failure_reason", nullable = true, length = 500)
    private String failureReason;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;
}
