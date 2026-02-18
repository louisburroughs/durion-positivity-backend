package com.positivity.location.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "locations")
@Getter
@Setter
public class Location {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "location_type_id", nullable = false)
    private LocationType type;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "location_parents", joinColumns = @JoinColumn(name = "location_id"))
    @MapKeyColumn(name = "parent_type")
    @MapKeyEnumerated(EnumType.STRING)
    @Column(name = "parent_location_id", nullable = false)
    private Map<ParentType, UUID> parents = new EnumMap<>(ParentType.class);

    @Column(name = "geographical_location_id", columnDefinition = "UUID")
    private UUID geographicalLocationId;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
    }
}
