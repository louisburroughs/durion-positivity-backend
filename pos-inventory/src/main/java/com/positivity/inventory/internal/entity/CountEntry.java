package com.positivity.inventory.internal.entity;

import jakarta.persistence.*;
import com.positivity.shared.id.UUIDv7Id;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Immutable record of a physical count performed by an auditor.
 * 
 * <p>
 * Based on clarification for issue #27:
 * <ul>
 * <li>Each count or recount creates a NEW CountEntry (never updated)</li>
 * <li>Recounts reference the prior CountEntry via recountOfCountEntryId</li>
 * <li>Sequence number tracks recount order (0=original, 1=first recount,
 * 2=second recount)</li>
 * <li>Maximum 2 recounts allowed (3 total entries per task)</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "count_entry")
@EntityListeners(AuditingEntityListener.class)
public class CountEntry {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "count_entry_id", updatable = false, nullable = false)
    private UUID countEntryId;

    /**
     * Reference to the cycle count task this entry belongs to.
     */
    @Column(name = "cycle_count_task_id", nullable = false)
    private UUID cycleCountTaskId;

    /**
     * ID of the auditor who performed this count.
     */
    @Column(name = "auditor_id", nullable = false, length = 100)
    private String auditorId;

    /**
     * Actual quantity counted by the auditor.
     * Must be a non-negative integer.
     */
    @Column(name = "actual_quantity", nullable = false)
    private Integer actualQuantity;

    /**
     * Expected quantity at the time of this count.
     * Stored for audit trail and historical reference.
     */
    @Column(name = "expected_quantity", nullable = false)
    private Integer expectedQuantity;

    /**
     * Calculated variance: actualQuantity - expectedQuantity.
     * Positive = surplus, Negative = shortage, Zero = perfect count.
     */
    @Column(name = "variance", nullable = false)
    private Integer variance;

    /**
     * Sequence number indicating recount order.
     * 0 = original count, 1 = first recount, 2 = second recount.
     */
    @Column(name = "recount_sequence_number", nullable = false)
    @Builder.Default
    private Integer recountSequenceNumber = 0;

    /**
     * Reference to the prior CountEntry if this is a recount.
     * Null for original counts.
     */
    @Column(name = "recount_of_count_entry_id")
    private UUID recountOfCountEntryId;

    /**
     * Timestamp when this count was performed and recorded.
     */
    @Column(name = "counted_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime countedAt = LocalDateTime.now();

    /**
     * Timestamp when this configuration was created.
     */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Timestamp when this configuration was last updated.
     */
    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * Indicates if this entry is a recount (convenience flag).
     * Derived from recountSequenceNumber > 0.
     */
    @Transient
    public boolean isRecount() {
        return recountSequenceNumber != null && recountSequenceNumber > 0;
    }
}
