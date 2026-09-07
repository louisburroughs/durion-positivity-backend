package com.positivity.price.internal.entity;

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
 * An hourly labor rate for a scope and a time window (#1575 Tier 0, T0-3).
 *
 * <p>The other operand of a labor line: pos-catalog says how long the operation takes,
 * this says what an hour of it costs (ADR-0054 sell-price split). Scope widens from
 * {@code (location, category)} through {@code (location, null)} and {@code (null, category)} to
 * the platform default {@code (null, null)}; a null field is "applies to everything", never
 * "unknown".
 *
 * <p>Rates are never edited in place. A change closes the current window and opens a new row, so
 * an invoice quoted at last month's rate stays explainable — the same reason
 * {@code service_labor_standard} supersedes rather than updates.
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "labor_rate",
        indexes = {
            @Index(
                    name = "ix_labor_rate_scope",
                    columnList = "location_id,operation_category,effective_from,effective_to")
        })
public class LaborRate {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    /** Null = the platform default rate, used where a location has authored none. */
    @Nullable
    @Column(name = "location_id")
    private UUID locationId;

    /** Null = applies to every operation category. */
    @Nullable
    @Enumerated(EnumType.STRING)
    @Column(name = "operation_category")
    private ServiceOperationCategory operationCategory;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "hourly_rate", nullable = false, precision = 10, scale = 4)
    private BigDecimal hourlyRate;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    /** Null = open-ended; the window is half-open [from, to). */
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
