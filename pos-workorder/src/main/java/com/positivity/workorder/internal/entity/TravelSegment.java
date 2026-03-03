package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.workorder.internal.enums.OnBehalfReasonCode;
import com.positivity.workorder.internal.enums.TravelSegmentStatus;
import com.positivity.workorder.internal.enums.TravelSegmentType;
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
 * Entity representing a single travel leg for a mobile work assignment.
 * Implements Story #67 Mobile Travel Segment Capture.
 */
@Entity
@Table(name = "travel_segment", indexes = {
        @Index(name = "idx_travel_segment_mobile_work_assignment_id", columnList = "mobileWorkAssignmentId"),
        @Index(name = "idx_travel_segment_technician_id", columnList = "technicianId"),
        @Index(name = "idx_travel_segment_status", columnList = "status"),
        @Index(name = "idx_travel_segment_assignment_technician", columnList = "mobileWorkAssignmentId, technicianId")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class TravelSegment {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID travelSegmentId;

    @NonNull
    @Column(nullable = false, columnDefinition = "UUID")
    private UUID mobileWorkAssignmentId;

    @NonNull
    @Column(nullable = false, columnDefinition = "UUID")
    private UUID technicianId;

    @NonNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TravelSegmentType segmentType;

    @NonNull
    @Column(nullable = false)
    private Instant startAt;

    @Nullable
    @Column
    private Instant endAt;

    @Nullable
    @Column(columnDefinition = "UUID")
    private UUID fromLocationId;

    @Nullable
    @Column(columnDefinition = "UUID")
    private UUID toLocationId;

    @Nullable
    @Column(columnDefinition = "UUID")
    private UUID workOrderId;

    @Nullable
    @Column
    private Integer durationMinutes;

    @Nullable
    @Column
    private Integer rawMinutes;

    // SET AT APPROVAL ONLY, not on capture/stop
    @Nullable
    @Column
    private Integer bufferedMinutes;

    @NonNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TravelSegmentStatus status;

    @NonNull
    @Column(nullable = false)
    private String createdBy;

    @Nullable
    @Column
    private String lastModifiedBy;

    @Nullable
    @Column
    private Instant lastModifiedAt;

    @Nullable
    @Column
    private String actedByUserId;

    @Nullable
    @Column(columnDefinition = "UUID")
    private UUID actedForPersonId;

    @Nullable
    @Enumerated(EnumType.STRING)
    @Column
    private OnBehalfReasonCode onBehalfReasonCode;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
