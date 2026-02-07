package com.positivity.vehiclefitment.internal.entity;

import java.util.UUID;

import com.positivity.shared.id.UUIDv7Generator;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single fitment tag for vehicle applicability.
 * Each tag is a key-value pair associated with a vehicle applicability hint.
 */
@Entity
@Table(name = "fitment_tags")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FitmentTag {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @PrePersist
    public void generateId() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
    }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TagType tagType;

    @Column(nullable = false)
    private String tagValue;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hint_id", nullable = false)
    private VehicleApplicabilityHint hint;
}
