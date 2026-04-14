package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.workorder.internal.enums.AdjustmentReasonCode;
import com.positivity.workorder.internal.enums.AdjustmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Entity representing a proposed adjustment to a time entry.
 * Implements Story #66 Approve Submitted Time.
 */
@Entity
@Table(
        name = "time_entry_adjustment",
        indexes = {@Index(name = "idx_tea_time_entry_id", columnList = "time_entry_id")})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class TimeEntryAdjustment {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID adjustmentId;

    @NonNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "time_entry_id", nullable = false)
    @ToString.Exclude
    private TimeEntry timeEntry;

    @NonNull
    @Column(nullable = false)
    private String requestedBy;

    @NonNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AdjustmentReasonCode reasonCode;

    @Nullable
    @Column(columnDefinition = "TEXT")
    private String notes;

    @Nullable
    @Column
    private Instant proposedStartAt;

    @Nullable
    @Column
    private Instant proposedEndAt;

    @Nullable
    @Column
    private Integer minutesDelta;

    @NonNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private AdjustmentStatus status = AdjustmentStatus.PROPOSED;

    @Nullable
    @Column
    private String approvedBy;

    @Nullable
    @Column
    private Instant approvedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @jakarta.persistence.Transient
    public UUID getTimeEntryId() {
        return timeEntry != null ? timeEntry.getTimeEntryId() : null;
    }

    @jakarta.persistence.Transient
    public void setTimeEntryId(UUID timeEntryId) {
        this.timeEntry = timeEntryId != null ? new TimeEntry(timeEntryId) : null;
    }
}
