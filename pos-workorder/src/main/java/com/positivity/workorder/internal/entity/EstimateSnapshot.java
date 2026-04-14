package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.workorder.internal.enums.EstimateStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Immutable snapshot of an estimate's complete state at a point in time.
 * Used for audit trail, version history, and compliance.
 *
 * Captures full estimate + all line items as JSON for historical reference.
 */
@Entity
@Table(name = "estimate_snapshot")
@Getter
@ToString(exclude = {"estimate"})
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class EstimateSnapshot {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID", updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "estimate_id", nullable = false, updatable = false)
    private Estimate estimate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private EstimateStatus status; // Status at time of snapshot

    @Column(nullable = false, columnDefinition = "TEXT", updatable = false)
    private String snapshotData; // JSON representation of full estimate state (estimate + all line items)

    @Column(nullable = false, updatable = false)
    private LocalDateTime capturedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column(nullable = false, updatable = false)
    private String capturedById; // User who created the snapshot

    @Column(length = 500, updatable = false)
    private String notes; // Optional notes about why snapshot was captured

    @Transient
    public UUID getEstimateId() {
        return estimate != null ? estimate.getId() : null;
    }

    public void setEstimateId(UUID estimateId) {
        this.estimate = estimateId != null ? new Estimate(estimateId) : null;
    }

    @PrePersist
    protected void prePersist() {
        if (capturedAt == null) {
            capturedAt = LocalDateTime.now(Clock.systemUTC());
        }
    }
}
