package com.positivity.catalog.internal.model;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Getter
@Setter
@Data
public class CompetitorXReference {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @PrePersist
    public void generateId() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
    }

    @ManyToOne
    private ProductEntity part; // Reference to the product
    @ManyToOne
    private ProductEntity competitorPart; // Reference to the Competitor product that matches
}
