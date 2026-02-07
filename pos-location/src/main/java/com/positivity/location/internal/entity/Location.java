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
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String postalCode;
    private String country;
    private String mailingAddress;

    // Reference to Person (responsible) by ID from pos-people
    private Long responsiblePersonId;

    @Builder.Default
    // Bi-directional parent-child relationship
    @OneToMany(mappedBy = "child", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<LocationParent> parents = new HashSet<>();

    @Builder.Default
    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<LocationParent> children = new HashSet<>();
}
