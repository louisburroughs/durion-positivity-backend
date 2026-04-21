package com.positivity.vehiclefitment.service;

import com.positivity.vehiclefitment.internal.entity.Make;
import com.positivity.vehiclefitment.internal.entity.Manufacturer;
import com.positivity.vehiclefitment.internal.entity.Model;
import com.positivity.vehiclefitment.internal.entity.VehicleType;
import com.positivity.vehiclefitment.internal.entity.VehicleVariable;
import com.positivity.vehiclefitment.internal.entity.VehicleVariableValue;
import com.positivity.vehiclefitment.service.dto.CreatePartFitmentRequest;
import com.positivity.vehiclefitment.service.dto.PartFitmentResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface VehicleFitmentService {

    String RESULTS = "Results";
    String FORMAT_JSON = "?format=json";

    List<VehicleVariable> getVehicleVariables();

    List<VehicleVariableValue> getVehicleVariableValues(UUID variableId);

    List<Manufacturer> getManufacturers();

    List<Make> getMakesByManufacturer(UUID manufacturerId);

    List<Model> getModelsByMake(UUID makeId);

    List<VehicleType> getVehicleTypesForMake(UUID makeId);

    /**
     * Creates a new part fitment record, resolving vehicle entities by name.
     */
    @NonNull
    PartFitmentResponse createFitment(@NonNull CreatePartFitmentRequest request);
}
