package com.positivity.vehiclefitment.service;

import com.positivity.vehiclefitment.internal.entity.Make;
import com.positivity.vehiclefitment.internal.entity.Manufacturer;
import com.positivity.vehiclefitment.internal.entity.Model;
import com.positivity.vehiclefitment.internal.entity.VehicleType;
import com.positivity.vehiclefitment.internal.entity.VehicleVariable;
import com.positivity.vehiclefitment.internal.entity.VehicleVariableValue;
import java.util.List;
import java.util.UUID;

public interface VehicleFitmentService {

    String RESULTS = "Results";
    String FORMAT_JSON = "?format=json";

    List<VehicleVariable> getVehicleVariables();

    List<VehicleVariableValue> getVehicleVariableValues(UUID variableId);

    List<Manufacturer> getManufacturers();

    List<Make> getMakesByManufacturer(UUID manufacturerId);

    List<Model> getModelsByMake(UUID makeId);

    List<VehicleType> getVehicleTypesForMake(UUID makeId);
}
