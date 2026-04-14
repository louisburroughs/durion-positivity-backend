package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
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
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Immutable append-only entity for auditing part adjustments (substitutions,
 * returns, corrections).
 *
 * <p>
 * This entity records all part adjustments on workorders, including:
 * </p>
 * <ul>
 * <li>SUBSTITUTION: Replace one part with another (original part preserved for
 * history)</li>
 * <li>ADDITIONAL_RETURN: Return unused quantity beyond normal return flow</li>
 * <li>CORRECTION: Administrative correction for data entry errors</li>
 * </ul>
 *
 * <p>
 * All fields are immutable after creation to maintain audit integrity.
 * </p>
 *
 * @see WorkorderPart
 */
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "workorder_part_adjustment_event",
        indexes = {
            @Index(name = "idx_part_adjustment_original_part", columnList = "original_part_id"),
            @Index(name = "idx_part_adjustment_workorder", columnList = "workorder_id"),
            @Index(name = "idx_part_adjustment_performed_at", columnList = "performed_at")
        })
public class WorkorderPartAdjustmentEvent {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    /**
     * The part being adjusted.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_part_id", nullable = false)
    @NonNull
    private WorkorderPart originalPart;

    /**
     * Denormalized workorder ID for efficient cross-part queries.
     */
    @Column(name = "workorder_id", nullable = false, columnDefinition = "UUID")
    @NonNull
    private UUID workorderId;

    /**
     * Type of adjustment: SUBSTITUTION, ADDITIONAL_RETURN, CORRECTION.
     */
    @Column(name = "adjustment_type", nullable = false, length = 30)
    @NonNull
    private String adjustmentType;

    /**
     * For SUBSTITUTION type: ID of the new part that replaced the original.
     * Null for other adjustment types.
     */
    @Column(name = "substituted_with_part_id", columnDefinition = "UUID")
    @Nullable
    private UUID substitutedWithPartId;

    /**
     * Signed quantity adjustment.
     * Negative for returns, positive for additions/corrections.
     */
    @Column(name = "quantity_adjustment", nullable = false, precision = 19, scale = 4)
    @NonNull
    private BigDecimal quantityAdjustment;

    /**
     * Reason for the adjustment (required for audit trail).
     */
    @Column(nullable = false, length = 1000)
    @NonNull
    private String reason;

    /**
     * User who performed the adjustment.
     */
    @Column(name = "performed_by", nullable = false, length = 255)
    @NonNull
    private String performedBy;

    /**
     * When the adjustment occurred.
     */
    @Column(name = "performed_at", nullable = false)
    @NonNull
    private Instant performedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * Optional additional context.
     */
    @Column(columnDefinition = "TEXT")
    @Nullable
    private String notes;

    @PrePersist
    protected void prePersist() {
        if (performedAt == null) {
            performedAt = Instant.now(Clock.systemUTC());
        }
    }
}
