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
import java.time.Instant;
import java.util.Objects;
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
 * Entity representing an adjustment applied to a travel segment.
 * Implements Story #67 Mobile Travel Segment Capture.
 */
@Entity
@Table(
        name = "travel_segment_adjustment",
        indexes = {@Index(name = "idx_tsa_travel_segment_id", columnList = "travel_segment_id")})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class TravelSegmentAdjustment {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID adjustmentId;

    @NonNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "travel_segment_id", nullable = false)
    @ToString.Exclude
    private TravelSegment travelSegment;

    @Nullable
    @Column
    private Instant adjustedStartAt;

    @Nullable
    @Column
    private Instant adjustedEndAt;

    @NonNull
    @Column(nullable = false, columnDefinition = "TEXT")
    private String adjustmentReason;

    @NonNull
    @Column(nullable = false)
    private String adjustedByUserId;

    @NonNull
    @Builder.Default
    @Column(nullable = false)
    private String approvalStatus = "PENDING";

    @Nullable
    @Column
    private String approvedByUserId;

    @Nullable
    @Column
    private Instant approvedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @jakarta.persistence.Transient
    public UUID getTravelSegmentId() {
        return travelSegment != null ? travelSegment.getTravelSegmentId() : null;
    }

    @jakarta.persistence.Transient
    public void setTravelSegmentId(UUID travelSegmentId) {
        // travelSegment is a required (optional=false) association; reject a null id rather than build a
        // reference entity with a null primary key (which would fail obscurely later at persist time).
        this.travelSegment =
                new TravelSegment(Objects.requireNonNull(travelSegmentId, "travelSegmentId must not be null"));
    }
}
