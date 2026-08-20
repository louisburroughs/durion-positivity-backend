package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.MeasurementMethod;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Immutable record of a physical count performed by an auditor.
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

    @ManyToOne(optional = false)
    @JoinColumn(name = "cycle_count_task_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private CycleCountTask cycleCountTask;

    @Column(name = "auditor_id", nullable = false, length = 100)
    private String auditorId;

    /**
     * The counted quantity in base UoM — what {@link #expectedQuantity} and {@link #variance} are
     * computed against. Equal to {@link #measuredQuantity} whenever {@link #uomCode} is null
     * (base-unit counts, which is every count until a bulk product is seeded); otherwise the
     * conversion of {@code measuredQuantity} from {@code uomCode} to base ({@code HALF_UP} — a
     * count is a measurement, not a reservation promise).
     */
    @Column(name = "actual_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal actualQuantity;

    @Column(name = "expected_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal expectedQuantity;

    @Column(name = "variance", nullable = false, precision = 19, scale = 4)
    private BigDecimal variance;

    /**
     * The quantity exactly as the counter keyed it, in {@link #uomCode} (or base UoM when
     * {@code uomCode} is null). Kept alongside the base-converted {@link #actualQuantity} so the
     * original measurement is never lost to a rounding conversion (owner spec, "Required Data:
     * Measured quantity").
     */
    @Column(name = "measured_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal measuredQuantity;

    /** Unit the count was physically measured in. Null means the product's base UoM. */
    @Column(name = "uom_code", length = 32)
    private String uomCode;

    /** How the quantity was obtained. Defaults to {@link MeasurementMethod#MANUAL_COUNT}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "measurement_method", nullable = false, length = 32)
    @Builder.Default
    private MeasurementMethod measurementMethod = MeasurementMethod.MANUAL_COUNT;

    /** {@code |variance| / |expectedQuantity| × 100}; {@code 100} when book quantity is zero. */
    @Column(name = "variance_percentage", precision = 9, scale = 4)
    private BigDecimal variancePercentage;

    /** Snapshot of the resolved absolute-tolerance bound at count time, if configured. */
    @Column(name = "allowed_tolerance_absolute", precision = 19, scale = 4)
    private BigDecimal allowedToleranceAbsolute;

    /** Snapshot of the resolved percentage-tolerance bound at count time, if configured. */
    @Column(name = "allowed_tolerance_percentage", precision = 7, scale = 4)
    private BigDecimal allowedTolerancePercentage;

    /**
     * Whether the variance fell within the tolerance resolved at count time. {@code true} means
     * the count was reconciled with no adjustment; {@code false} means it was flagged for
     * investigation and review (owner spec: "If variance exceeds tolerance, require investigation
     * and approval before posting an inventory adjustment").
     */
    @Column(name = "within_tolerance", nullable = false)
    @Builder.Default
    private boolean withinTolerance = true;

    /**
     * Free-text reason for an out-of-tolerance variance. Optional at count-submission time — the
     * count and the eventual adjustment are separate transactions, and the adjustment's own
     * {@code reasonCode} (required, non-blank) is what ultimately gates posting.
     */
    @Column(name = "variance_reason", length = 500)
    private String varianceReason;

    @Column(name = "recount_sequence_number", nullable = false)
    @Builder.Default
    private Integer recountSequenceNumber = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recount_of_count_entry_id")
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private CountEntry recountOfCountEntry;

    public UUID getRecountOfCountEntryId() {
        return recountOfCountEntry != null ? recountOfCountEntry.getCountEntryId() : null;
    }

    @Column(name = "counted_at", nullable = false, updatable = false)
    private Instant countedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    @Transient
    public boolean isRecount() {
        return recountSequenceNumber != null && recountSequenceNumber > 0;
    }
}
