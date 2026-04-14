package com.positivity.vehicle.internal.dto;

import java.util.UUID;
import lombok.Data;

@Data
public class VehicleLegacyRequest {
    private UUID id;
    private String make;
    private String model;
    private Integer year;
    private String vin;
    private String vehicleType;
}
