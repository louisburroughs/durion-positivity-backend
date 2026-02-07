package com.positivity.vehiclefitment.internal.entity;

import jakarta.persistence.*;
import com.positivity.shared.id.UUIDv7Generator;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a vehicle applicability hint for a product/SKU.
 * Contains a collection of fitment tags that describe vehicle compatibility.
 */
@Entity
@Table(name = "vehicle_applicability_hints")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VehicleApplicabilityHint {
    
    @Id
    @Column(columnDefinition = "UUID")
    private UUID hintId;

    @PrePersist
    public void generateId() {
        if (hintId == null) {
            hintId = UUIDv7Generator.generate();
        }
    }
    
    @Column(nullable = false)
    private Long productId;
    
    @OneToMany(mappedBy = "hint", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<FitmentTag> fitmentTags = new ArrayList<>();
    
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @Column(nullable = false)
    private LocalDateTime updatedAt;
    
    @Column
    private String createdBy;
    
    @Column
    private String updatedBy;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    /**
     * Helper method to add a fitment tag to this hint.
     */
    public void addFitmentTag(FitmentTag tag) {
        fitmentTags.add(tag);
        tag.setHint(this);
    }
    
    /**
     * Helper method to remove a fitment tag from this hint.
     */
    public void removeFitmentTag(FitmentTag tag) {
        fitmentTags.remove(tag);
        tag.setHint(null);
    }
}
