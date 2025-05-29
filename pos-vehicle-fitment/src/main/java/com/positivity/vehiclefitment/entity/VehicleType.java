package com.positivity.vehiclefitment.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
public class VehicleType {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne
    @JoinColumn(name = "make_id")
    private Make make;
    private String vehicleTypeName;
    private String vehicleTypeId;
    private LocalDateTime cacheTimestamp;

}

