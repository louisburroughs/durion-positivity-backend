package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.workorder.internal.enums.TimeEntryStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing a time entry for a person on a workorder.
 * Implements Story #66 Approve Submitted Time.
 */
@Entity
@Table(name = "time_entry", indexes = {
        @Index(name = "idx_time_entry_person_id", columnList = "personId"),
        @Index(name = "idx_time_entry_work_order_id", columnList = "workOrderId"),
        @Index(name = "idx_time_entry_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class TimeEntry {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID timeEntryId;

    @NonNull
    @Column(nullable = false, columnDefinition = "UUID")
    private UUID personId;

    @NonNull
    @Column(nullable = false, columnDefinition = "UUID")
    private UUID workOrderId;

    @NonNull
    @Column(nullable = false)
    private Instant startAt;

    @Nullable
    @Column
    private Instant endAt;

    @NonNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TimeEntryStatus status;

    @Nullable
    @Column
    private Instant submittedAt;

    // actor who approved or rejected
    @Nullable
    @Column
    private String decisionByUserId;

    @Nullable
    @Column
    private Instant decisionAtUtc;

    @Nullable
    @Column(columnDefinition = "TEXT")
    private String rejectionReason;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
