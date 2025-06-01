package com.positivity.catalog.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;


@Slf4j
@Data
@Entity
@Table(name = "dimensions")
@AllArgsConstructor
@NoArgsConstructor
public class DimensionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Enumerated(EnumType.STRING)
    private DimensionType dimensionType;

    private String description;
    private String unitOfMeasure;
    private double dimensionValue;

}
