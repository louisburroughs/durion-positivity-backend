package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
@Data
@Entity
@Table(name = "dimensions")
@AllArgsConstructor
@NoArgsConstructor
public class DimensionEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @PrePersist
    public void generateId() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
    }

    @Enumerated(EnumType.STRING)
    private DimensionType dimensionType;

    private String description;
    private String unitOfMeasure;
    private double dimensionValue;

}
