package com.positivity.nhtsa.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
public class VehicleVariableValue {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long variableId;
    private String value;
    private String valueId;
    private LocalDateTime cacheTimestamp;

}

