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
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Represents a labor entry tracking work performed on a workorder service item.
 * Immutable once created, except for endTime and hoursWorked when stopping a
 * session.
 * 
 * <p>
 * Implements CAP-005 Story #159 - Record Labor Performed
 */
@Entity
@Table(name = "workorder_labor_entry", indexes = {
        @Index(name = "idx_labor_workorder_id", columnList = "workorderId"),
        @Index(name = "idx_labor_service_id", columnList = "workorderServiceId"),
        @Index(name = "idx_labor_start_time", columnList = "startTime")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class WorkorderLaborEntry {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @NonNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workorder_id", nullable = false, updatable = false)
    private Workorder workorder;

    @NonNull
    @Column(name = "workorder_id", nullable = false, updatable = false, insertable = false, columnDefinition = "UUID")
    private UUID workorderId;

    @NonNull
    @Column(nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID workorderServiceId;

    @NonNull
    @Column(nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID technicianId;

    @NonNull
    @Column(nullable = false, updatable = false)
    private LocalDateTime startTime;

    @Nullable
    @Column
    private LocalDateTime endTime;

    @NonNull
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal hoursWorked;

    @Nullable
    @Column(columnDefinition = "TEXT")
    private String notes;

    @Nullable
    @Column(columnDefinition = "TEXT")
    private String adjustmentReason;

    // Audit fields
    @NonNull
    @Column(nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID createdBy;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    /**
     * Check if this labor session is still active (not stopped).
     */
    public boolean isActive() {
        return endTime == null;
    }

    /**
     * Stop this labor session and calculate hours worked.
     * 
     * @param stopTime the time the session ended
     */
    public void stop(@NonNull LocalDateTime stopTime) {
        if (endTime != null) {
            throw new IllegalStateException("Labor session already stopped");
        }
        if (stopTime.isBefore(startTime)) {
            throw new IllegalArgumentException("End time cannot be before start time");
        }
        this.endTime = stopTime;
        this.hoursWorked = calculateHours(startTime, stopTime);
    }

    /**
     * Manually adjust hours worked with a reason.
     * 
     * @param hours  the new hours worked value
     * @param reason the reason for adjustment
     */
    public void adjustHours(@NonNull BigDecimal hours, @NonNull String reason) {
        if (hours.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Hours worked cannot be negative");
        }
        this.hoursWorked = hours;
        this.adjustmentReason = reason;
    }

    private BigDecimal calculateHours(LocalDateTime start, LocalDateTime end) {
        long minutes = java.time.Duration.between(start, end).toMinutes();
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP);
    }
}
