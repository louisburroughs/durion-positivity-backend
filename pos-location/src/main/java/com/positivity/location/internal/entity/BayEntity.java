package com.positivity.location.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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

/**
 * Bay aggregate for location service operations.
 *
 * Issue: CAP-136 #77
 */
@Entity
@Table(name = "bays", uniqueConstraints = {
        @UniqueConstraint(name = "uq_bays_location_normalized_name", columnNames = { "location_id", "normalized_name" })
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BayEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "location_id", nullable = false, columnDefinition = "UUID")
    private UUID locationId;

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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_modified_at", nullable = false)
    private Instant lastModifiedAt;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
        normalizedName = normalizeName(name);
        Instant now = Instant.now();
        createdAt = now;
        lastModifiedAt = now;
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
        lastModifiedAt = Instant.now();
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
