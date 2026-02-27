package com.positivity.vehicle.internal.dto;

import com.positivity.vehicle.internal.entity.Car;
import com.positivity.vehicle.internal.entity.CommercialTruck;
import com.positivity.vehicle.internal.entity.PassengerTruck;
import com.positivity.vehicle.internal.entity.Van;
import com.positivity.vehicle.internal.entity.VehicleEntity;

public final class VehicleLegacyMapper {
    private VehicleLegacyMapper() {
    }

    public static VehicleEntity toEntity(VehicleLegacyRequest request) {
        VehicleEntity entity = createVehicleByType(request.getVehicleType());
        if (request.getId() != null) {
            entity.setId(request.getId());
        }
        applyCoreFields(entity, request);
        entity.setVIN(request.getVin());
        return entity;
    }

    public static void applyCoreFields(VehicleEntity entity, VehicleLegacyRequest request) {
        entity.setMake(request.getMake());
        entity.setModel(request.getModel());
        entity.setYear(request.getYear() == null ? 0 : request.getYear());
    }

    public static VehicleLegacyResponse toResponse(VehicleEntity entity) {
        return VehicleLegacyResponse.builder()
                .id(entity.getId())
                .make(entity.getMake())
                .model(entity.getModel())
                .year(entity.getYear())
                .vin(entity.getVIN())
                .vehicleType(resolveVehicleType(entity))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private static VehicleEntity createVehicleByType(String vehicleType) {
        if (vehicleType == null) {
            return new Car();
        }
        return switch (vehicleType.trim().toUpperCase()) {
            case "VAN" -> new Van();
            case "COMMERCIAL_TRUCK" -> new CommercialTruck();
            case "PASSENGER_TRUCK", "TRUCK" -> new PassengerTruck();
            default -> new Car();
        };
    }

    private static String resolveVehicleType(VehicleEntity entity) {
        if (entity instanceof Van) {
            return "VAN";
        }
        if (entity instanceof CommercialTruck) {
            return "COMMERCIAL_TRUCK";
        }
        if (entity instanceof PassengerTruck) {
            return "PASSENGER_TRUCK";
        }
        return "CAR";
    }
}
