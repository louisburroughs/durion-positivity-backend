package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;
@Slf4j
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "dimensions")
@AllArgsConstructor
@NoArgsConstructor
public class DimensionEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Enumerated(EnumType.STRING)
    private DimensionType dimensionType;

    private String description;
    private String unitOfMeasure;
    private double dimensionValue;

}
