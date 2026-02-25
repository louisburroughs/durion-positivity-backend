package com.positivity.vehiclefitment.internal.entity;

import jakarta.persistence.*;
import com.positivity.shared.id.UUIDv7Generator;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a vehicle applicability hint for a product/SKU.
 * Contains a collection of fitment tags that describe vehicle compatibility.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "vehicle_applicability_hints")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VehicleApplicabilityHint {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID hintId;

    @Column(nullable = false)
    private UUID productId;

    @OneToMany(mappedBy = "hint", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<FitmentTag> fitmentTags = new ArrayList<>();

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column
    private String createdBy;

    @Column
    private String updatedBy;

    @PrePersist
    protected void onCreate() {
        if (hintId == null) {
            hintId = UUIDv7Generator.generate();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
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
