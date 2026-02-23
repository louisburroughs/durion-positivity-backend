package com.positivity.location.internal.entity;

import jakarta.persistence.*;
import com.positivity.shared.id.UUIDv7Generator;
import lombok.*;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Location {
    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @PrePersist
    public void generateId() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
        normalizeDerivedFields();
    }

    @PreUpdate
    public void preUpdate() {
        normalizeDerivedFields();
    }

    private void normalizeDerivedFields() {
        normalizedName = normalizeName(name);
        if (status == null || status.isBlank()) {
            status = active ? "ACTIVE" : "INACTIVE";
        }
    }

    private String normalizeName(String value) {
        if (value == null) {
            return null;
        }
        return value.toLowerCase(Locale.ROOT).trim();
    }

    private String name;
    @Column(name = "normalized_name")
    private String normalizedName;
    /** Unique immutable business code for this location (e.g. "MAIN-WS-001"). */
    @Column(unique = true)
    private String code;
    private String status;
    private String timezone;
    @Column(name = "operating_hours", columnDefinition = "TEXT")
    private String operatingHours;
    @Column(name = "holiday_closures", columnDefinition = "TEXT")
    private String holidayClosures;
    private Integer checkInBufferMinutes;
    private Integer cleanupBufferMinutes;
    @Version
    private Long version;

    /**
     * Reference to the GeographicalLocation entity in pos-location (ADR-0016).
     * Other services MUST NOT replicate address data; they store this ID and
     * query pos-location for full address details.
     */
    @Column(name = "geographical_location_id")
    private UUID geographicalLocationId;

    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String postalCode;
    private String country;
    private String mailingAddress;
    @Builder.Default
    @Column(name = "is_active", nullable = false, columnDefinition = "boolean default true")
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_type_id")
    private LocationType type;

    // Reference to Person (responsible) by ID from pos-people
    private Long responsiblePersonId;

    @Builder.Default
    // Bi-directional parent-child relationship
    @OneToMany(mappedBy = "child", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<LocationParent> parents = new HashSet<>();

    @Builder.Default
    @OneToMany(mappedBy = "parent", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<LocationParent> children = new HashSet<>();
}
