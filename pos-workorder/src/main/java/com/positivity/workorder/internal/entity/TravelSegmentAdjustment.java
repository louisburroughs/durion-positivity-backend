package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing an adjustment applied to a travel segment.
 * Implements Story #67 Mobile Travel Segment Capture.
 */
@Entity
@Table(name = "travel_segment_adjustment", indexes = {
        @Index(name = "idx_tsa_travel_segment_id", columnList = "travelSegmentId")
})
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
    @Column(nullable = false, columnDefinition = "UUID")
    private UUID travelSegmentId;

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
}
