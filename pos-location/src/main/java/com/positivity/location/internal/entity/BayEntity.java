package com.positivity.location.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Bay aggregate for location service operations.
 *
 * Issue: CAP-136 #77
 */
@Entity
@Table(
        name = "bays",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_bays_location_normalized_name",
                    columnNames = {"location_id", "normalized_name"})
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class BayEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false)
    private Location location;

    public UUID getLocationId() {
        return location != null ? location.getId() : null;
    }

    @Column(nullable = false)
    private String name;

    @Column(name = "normalized_name", nullable = false)
    private String normalizedName;

    @Column(name = "bay_type", nullable = false, length = 50)
    private String bayType;

    @Builder.Default
    @Column(nullable = false, length = 50)
    private String status = "ACTIVE";

    @Column(name = "max_concurrent_vehicles", nullable = false)
    private Integer maxConcurrentVehicles;

    @Builder.Default
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "service_capability_ids", columnDefinition = "TEXT")
    private List<String> serviceCapabilityIds = new ArrayList<>();

    @Builder.Default
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "skill_requirement_ids", columnDefinition = "TEXT")
    private List<String> skillRequirementIds = new ArrayList<>();

    @CreatedDate
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "last_modified_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        normalizedName = normalizeName(name);
        if (status == null || status.isBlank()) {
            status = "ACTIVE";
        }
        if (serviceCapabilityIds == null) {
            serviceCapabilityIds = new ArrayList<>();
        }
        if (skillRequirementIds == null) {
            skillRequirementIds = new ArrayList<>();
        }
    }

    @PreUpdate
    void onUpdate() {
        normalizedName = normalizeName(name);
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
