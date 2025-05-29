package com.positivity.vehiclefitment.model;

import com.positivity.vehiclefitment.entity.*;
import jakarta.persistence.*;
import lombok.Data;

import java.util.List;


@Entity
@Data
public class PartFitmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;
    private Long partNumberId; // Reference to the product this fitment applies to
    @ManyToOne
    @JoinColumn(name = "vehicle_manufacturer_id")
    private Manufacturer vehicleManufacturer;
    @ManyToOne
    @JoinColumn(name = "vehicle_make_id")
    private Make vehicleMake;  // vehicle_make_name
    @ManyToOne
    @JoinColumn(name = "vehicle_model_id")
    private Model vehicleModel; // vehicle_model_name
    @ManyToOne
    @JoinColumn(name = "vehicle_type_id")
    private VehicleType vehicleType; // (e.g., "Car," "Truck," "SUV")
    private String vehicleYear; // (Range or individual year)
    private String engineType; // (e.g., "2.0L I4," "3.5L V6")
    private String submodel; // (e.g., "LX," "SE," "Limited")
    @ManyToMany
    private List<VehicleVariableValue> vehicleVariableValues;
    private String notes;// (Specific fitment notes, e.g., "Except with Off-Road Package," "Requires Modification")
}
