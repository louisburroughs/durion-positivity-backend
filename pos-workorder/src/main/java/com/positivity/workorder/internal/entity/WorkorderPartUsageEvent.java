package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.*;
import lombok.*;
import org.jspecify.annotations.NonNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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
@Table(name = "workorder_part_usage_event", indexes = {
        @Index(name = "idx_workorder_part_usage_part_id", columnList = "workorder_part_id"),
        @Index(name = "idx_workorder_part_usage_workorder_id", columnList = "workorder_id"),
        @Index(name = "idx_workorder_part_usage_performed_at", columnList = "performed_at")
})
public class WorkorderPartUsageEvent {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @PrePersist
    public void generateId() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
        if (performedAt == null) {
            performedAt = Instant.now();
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
    @Column(nullable = false, columnDefinition = "UUID")
    @NonNull
    private UUID performedBy;

    /**
     * When the event was performed/recorded
     */
    @Column(nullable = false)
    @NonNull
    private Instant performedAt;

    /**
     * Optional notes about this event
     */
    @Column(columnDefinition = "TEXT")
    private String notes;
}
