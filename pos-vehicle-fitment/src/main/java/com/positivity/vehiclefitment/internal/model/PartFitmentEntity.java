package com.positivity.vehiclefitment.internal.model;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.vehiclefitment.internal.entity.*;
import jakarta.persistence.*;
import lombok.Data;

import java.util.List;
import java.util.UUID;


@Entity
@Data
public class PartFitmentEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;
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

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUIDv7Generator.generate();
        }
    }
}
