package com.positivity.location.internal.entity;

import jakarta.persistence.*;
import com.positivity.shared.id.UUIDv7Generator;
import lombok.*;
import java.util.HashSet;
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
    }

    private String name;
    /** Unique immutable business code for this location (e.g. "MAIN-WS-001"). */
    @Column(unique = true)
    private String code;

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
