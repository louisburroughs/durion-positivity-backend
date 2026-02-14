package com.positivity.workorder.internal.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import com.positivity.shared.id.UUIDv7Generator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Immutable snapshot of an estimate's complete state at a point in time.
 * Used for audit trail, version history, and compliance.
 * 
 * Captures full estimate + all line items as JSON for historical reference.
 */
@Entity
@Table(name = "estimate_snapshot")
@Getter
@ToString
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EstimateSnapshot {

    @Id
    @Column(columnDefinition = "UUID", updatable = false)
    private UUID id;

    @Column(nullable = false, columnDefinition = "UUID", updatable = false)
    private UUID estimateId; // Reference to the source estimate

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private EstimateStatus status; // Status at time of snapshot

    @Column(nullable = false, columnDefinition = "TEXT", updatable = false)
    private String snapshotData; // JSON representation of full estimate state (estimate + all line items)

    @Column(nullable = false, updatable = false)
    private LocalDateTime capturedAt;

    @Column(nullable = false, updatable = false)
    private String capturedById; // User who created the snapshot

    @Column(length = 500, updatable = false)
    private String notes; // Optional notes about why snapshot was captured

    @PrePersist
    protected void prePersist() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
        if (capturedAt == null) {
            capturedAt = LocalDateTime.now();
        }
    }
}
