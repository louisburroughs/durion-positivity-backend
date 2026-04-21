package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.time.TimeSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Immutable append-only entity tracking parts usage events on workorders.
 *
 * CAP:005 Story #158 - Parts Usage Tracking
 *
 * Records ISSUE, CONSUME, and RETURN events for parts on workorders.
 * These events form an audit trail and are aggregated to compute
 * quantityIssued, quantityConsumed, and quantityReturned on WorkorderPart.
 */
@Entity
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "workorder_part_usage_event",
        indexes = {
            @Index(name = "idx_workorder_part_usage_part_id", columnList = "workorder_part_id"),
            @Index(name = "idx_workorder_part_usage_workorder_id", columnList = "workorder_id"),
            @Index(name = "idx_workorder_part_usage_performed_at", columnList = "performed_at")
        })
public class WorkorderPartUsageEvent {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @PrePersist
    public void generateId() {
        if (performedAt == null) {
            performedAt = TimeSource.instant();
        }
    }

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "workorder_part_id", nullable = false)
    @NonNull
    private WorkorderPart workorderPart;

    /**
     * Denormalized workorder ID for efficient queries across all parts
     */
    @Column(nullable = false, columnDefinition = "UUID")
    @NonNull
    private UUID workorderId;

    /**
     * Event type: ISSUE, CONSUME, or RETURN
     */
    @Column(nullable = false, length = 20)
    @NonNull
    private String eventType;

    /**
     * Quantity for this event (always positive)
     */
    @Column(nullable = false, precision = 19, scale = 4)
    @NonNull
    private BigDecimal quantity;

    /**
     * User who performed/recorded this event
     */
    @Column(nullable = false, length = 255)
    @NonNull
    private String performedBy;

    /**
     * When the event was performed/recorded
     */
    @Column(nullable = false)
    @NonNull
    private Instant performedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * Optional notes about this event
     */
    @Column(columnDefinition = "TEXT")
    private String notes;
}
