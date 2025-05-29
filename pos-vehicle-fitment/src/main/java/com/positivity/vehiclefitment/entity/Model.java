package com.positivity.vehiclefitment.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
public class Model {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    @ManyToOne
    private Make make; // Reference to the Make entity
    private LocalDateTime cacheTimestamp;

}

