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
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Entity representing a single travel leg for a mobile work assignment.
 * Implements Story #67 Mobile Travel Segment Capture.
 */
@Entity
@Table(
        name = "travel_segment",
        indexes = {
            @Index(name = "idx_travel_segment_mobile_work_assignment_id", columnList = "mobileWorkAssignmentId"),
            @Index(name = "idx_travel_segment_technician_id", columnList = "technicianId"),
            @Index(name = "idx_travel_segment_status", columnList = "status"),
            @Index(
                    name = "idx_travel_segment_assignment_technician",
                    columnList = "mobileWorkAssignmentId, technicianId")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class TravelSegment {
    // PK-only reference constructor used by association setters (e.g. TravelSegmentAdjustment);
    // intentionally leaves the other @NonNull persistent columns unset on the reference proxy.
    @SuppressWarnings("java:S2637")
    public TravelSegment(UUID travelSegmentId) {
        this.travelSegmentId = travelSegmentId;
    }

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
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "work_order_id")
    @ToString.Exclude
    private Workorder workOrder;

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

    @jakarta.persistence.Transient
    public UUID getWorkOrderId() {
        return workOrder != null ? workOrder.getId() : null;
    }

    @jakarta.persistence.Transient
    public void setWorkOrderId(UUID workOrderId) {
        this.workOrder = workOrderId != null ? new Workorder(workOrderId) : null;
    }
}
