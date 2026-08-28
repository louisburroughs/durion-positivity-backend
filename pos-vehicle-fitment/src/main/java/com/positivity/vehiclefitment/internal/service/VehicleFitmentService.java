package com.positivity.vehiclefitment.internal.service;

import com.positivity.vehiclefitment.internal.dto.MakeResponse;
import com.positivity.vehiclefitment.internal.dto.ManufacturerResponse;
import com.positivity.vehiclefitment.internal.dto.ModelResponse;
import com.positivity.vehiclefitment.internal.dto.VehicleTypeResponse;
import com.positivity.vehiclefitment.internal.entity.VehicleVariable;
import com.positivity.vehiclefitment.internal.entity.VehicleVariableValue;
import com.positivity.vehiclefitment.internal.service.dto.CreatePartFitmentRequest;
import com.positivity.vehiclefitment.internal.service.dto.PartFitmentResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface VehicleFitmentService {

    String RESULTS = "Results";
    String FORMAT_JSON = "?format=json";

    List<VehicleVariable> getVehicleVariables();

    List<VehicleVariableValue> getVehicleVariableValues(UUID variableId);

    @NonNull
    List<ManufacturerResponse> getManufacturers();

    @NonNull
    List<MakeResponse> getMakesByManufacturer(@NonNull UUID manufacturerId);

    @NonNull
    List<ModelResponse> getModelsByMake(@NonNull UUID makeId);

    @NonNull
    List<VehicleTypeResponse> getVehicleTypesForMake(@NonNull UUID makeId);

    /**
     * Creates a new part fitment record, resolving vehicle entities by name.
     */
    @NonNull
    PartFitmentResponse createFitment(@NonNull CreatePartFitmentRequest request);
}
