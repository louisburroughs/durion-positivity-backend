package com.positivity.vehicle.model;

import com.positivity.vehicle.Vehicle;
import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "vehicle_type")
public abstract class VehicleEntity implements Vehicle {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String make;
    private String model;
    private int year;
    private String vin;

    public String getVIN() { return vin; }
    public void setVIN(String vin) { this.vin = vin; }
}
