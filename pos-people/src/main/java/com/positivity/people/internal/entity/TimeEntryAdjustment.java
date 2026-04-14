package com.positivity.people.internal.entity;

import com.positivity.people.internal.enums.AdjustmentStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "time_entry_adjustment")
@Data
public class TimeEntryAdjustment {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "adjustment_id", columnDefinition = "UUID", updatable = false, nullable = false)
    private UUID adjustmentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "time_entry_id", nullable = false)
    private TimeEntry timeEntry;

    public UUID getTimeEntryId() {
        return timeEntry != null ? timeEntry.getTimeEntryId() : null;
    }

    @Column(name = "reason_code", length = 200)
    private String reasonCode;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "proposed_start_at")
    private Instant proposedStartAt;

    @Column(name = "proposed_end_at")
    private Instant proposedEndAt;

    @Column(name = "minutes_delta")
    private Integer minutesDelta;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 50)
    private AdjustmentStatus status;

    @Column(name = "created_by")
    private String createdBy;

    @CreatedDate
    @Column(name = "created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;
}
