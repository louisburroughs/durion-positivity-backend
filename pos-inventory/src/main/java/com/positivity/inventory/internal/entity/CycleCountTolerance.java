package com.positivity.inventory.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Tolerance-based cycle-count reconciliation configuration (ADR-0055 stage 4, #1416; issue #1414
 * owner spec).
 *
 * <p>A count's variance against book quantity is compared to the tolerance resolved for its
 * product and storage location — most-specific scope wins: product+location, then product alone,
 * then location alone, then no row at all. {@code productId} and {@code storageLocation} may each
 * be null to declare a broader scope; a row with both null is the global default. Neither bound is
 * required: {@code absoluteTolerance} and {@code percentageTolerance} may each be null, and both
 * null is zero tolerance — today's behavior, preserved as the default until a tolerance is seeded
 * for a product or location.
 *
 * <p>{@code storageLocation} matches {@link CycleCountTask#getBinLocation()}'s own convention
 * (free text that may hold a location UUID's string form — see {@code
 * CycleCountConflictDetector}) rather than a foreign key to a location table, since the task table
 * this scopes has no location UUID column to key against.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "cycle_count_tolerance")
public class CycleCountTolerance {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "tolerance_id", updatable = false, nullable = false)
    private UUID toleranceId;

    /** Catalog product this tolerance scopes to, or {@code null} for a product-agnostic row. */
    @Column(name = "product_id")
    private UUID productId;

    /**
     * Storage location this tolerance scopes to, or {@code null} for a location-agnostic row.
     * Matches {@code cycle_count_task.bin_location} — free text, may hold a location UUID's
     * string form.
     */
    @Column(name = "storage_location", length = 100)
    private String storageLocation;

    /** Maximum acceptable absolute variance, in the product's base UoM. Null = not configured. */
    @Column(name = "absolute_tolerance", precision = 19, scale = 4)
    private BigDecimal absoluteTolerance;

    /**
     * Maximum acceptable variance as a percentage of book quantity (e.g. {@code 1.5} = 1.5%).
     * Null = not configured.
     */
    @Column(name = "percentage_tolerance", precision = 7, scale = 4)
    private BigDecimal percentageTolerance;

    /** Whether this row is in effect. Inactive rows are excluded from resolution entirely. */
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
