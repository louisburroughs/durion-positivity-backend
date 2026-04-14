package com.positivity.vehicle.internal.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class VehicleLegacyResponse {
    UUID id;
    String make;
    String model;
    int year;
    String vin;
    String vehicleType;
    Instant createdAt;
    Instant updatedAt;
}
