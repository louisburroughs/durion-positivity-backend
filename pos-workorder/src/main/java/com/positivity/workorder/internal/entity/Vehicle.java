package com.positivity.workorder.internal.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Vehicle {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String vin;
    private String make;
    private String model;
    private String year;
    private String licensePlate;
    private String color;
}
