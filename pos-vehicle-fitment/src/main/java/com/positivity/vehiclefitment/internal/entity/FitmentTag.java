package com.positivity.vehiclefitment.internal.entity;

import jakarta.persistence.*;
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
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TagType tagType;
    
    @Column(nullable = false)
    private String tagValue;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hint_id", nullable = false)
    private VehicleApplicabilityHint hint;
}
