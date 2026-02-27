package com.positivity.vehiclefitment.internal.dto;

import java.util.UUID;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class VehicleTypeResponse {
    UUID id;
    UUID makeId;
    String vehicleTypeId;
    String vehicleTypeName;
}
