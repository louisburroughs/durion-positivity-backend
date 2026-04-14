package com.positivity.vehiclefitment.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
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
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Represents a single fitment tag for vehicle applicability.
 * Each tag is a key-value pair associated with a vehicle applicability hint.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "fitment_tags")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FitmentTag {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TagType tagType;

    @Column(nullable = false)
    private String tagValue;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hint_id", nullable = false)
    private VehicleApplicabilityHint hint;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
