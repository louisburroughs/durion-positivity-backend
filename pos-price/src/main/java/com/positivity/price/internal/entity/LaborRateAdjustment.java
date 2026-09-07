package com.positivity.price.internal.entity;

import com.positivity.price.internal.enums.LaborRateAdjustmentType;
import com.positivity.price.internal.enums.ServiceOperationCategory;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One step of a shop's labor matrix (#1575 Tier 0 "shop-specific pricing rules", T0-3).
 *
 * <p>The matrix is what a shop charges <em>beyond</em> the base rate for conditions the guide
 * time assumes away: corrosion, restricted access, after-hours work, a fleet contract discount.
 * Steps are opt-in — a quote names the codes that apply — and are applied in {@code sequence}
 * order, because percentage steps compound and +15% then −10% is not −10% then +15%.
 *
 * <p>This is shop policy, not catalog master data, which is why it lives beside the rate rather
 * than beside the time (#1569 "the shop labor matrix ... belongs outside ServiceEntity").
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "labor_rate_adjustment",
        indexes = {
            @Index(name = "ix_lra_scope", columnList = "location_id,operation_category,effective_from,effective_to")
        })
public class LaborRateAdjustment {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    /** Null = the platform default matrix. */
    @Nullable
    @Column(name = "location_id")
    private UUID locationId;

    /** Null = applies to every operation category. */
    @Nullable
    @Enumerated(EnumType.STRING)
    @Column(name = "operation_category")
    private ServiceOperationCategory operationCategory;

    /** The code a quote names to opt this step in, e.g. {@code CORROSION}. */
    @Column(name = "adjustment_code", nullable = false, length = 64)
    private String adjustmentCode;

    @Nullable
    @Column(length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", nullable = false, length = 16)
    private LaborRateAdjustmentType adjustmentType;

    @Column(name = "adjustment_value", nullable = false, precision = 10, scale = 4)
    private BigDecimal adjustmentValue;

    /** Application order; part of the answer, not a display hint, because percentages compound. */
    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Nullable
    @Column(name = "effective_to")
    private Instant effectiveTo;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
