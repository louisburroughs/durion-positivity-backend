package com.positivity.vehiclefitment.internal.dto;

import com.positivity.vehiclefitment.internal.entity.Make;
import com.positivity.vehiclefitment.internal.entity.Manufacturer;
import com.positivity.vehiclefitment.internal.entity.Model;
import com.positivity.vehiclefitment.internal.entity.VehicleType;
import java.util.Comparator;
import java.util.UUID;

public final class VehicleFitmentMapper {
    private VehicleFitmentMapper() {}

    public static ManufacturerResponse toManufacturerResponse(Manufacturer manufacturer) {
        return ManufacturerResponse.builder()
                .id(manufacturer.getId())
                .name(manufacturer.getName())
                .build();
    }

    public static MakeResponse toMakeResponse(Make make) {
        return MakeResponse.builder()
                .id(make.getId())
                .name(make.getName())
                .manufacturerIds(make.getManufacturers().stream()
                        .map(Manufacturer::getId)
                        .sorted(Comparator.comparing(UUID::toString))
                        .toList())
                .build();
    }

    public static ModelResponse toModelResponse(Model model) {
        return ModelResponse.builder()
                .id(model.getId())
                .name(model.getName())
                .makeId(model.getMake() == null ? null : model.getMake().getId())
                .build();
    }

    public static VehicleTypeResponse toVehicleTypeResponse(VehicleType vehicleType) {
        return VehicleTypeResponse.builder()
                .id(vehicleType.getId())
                .makeId(
                        vehicleType.getMake() == null
                                ? null
                                : vehicleType.getMake().getId())
                .vehicleTypeId(vehicleType.getVehicleTypeId())
                .vehicleTypeName(vehicleType.getVehicleTypeName())
                .build();
    }
}
