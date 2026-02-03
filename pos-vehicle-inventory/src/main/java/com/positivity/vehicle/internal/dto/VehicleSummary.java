package com.positivity.vehicle.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Vehicle summary for search results (CAP:091 Story #103).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VehicleSummary {
    private UUID vehicleId;
    private String vin;
    private String unitNumber;
    private String licensePlate;
    private String description;
    private Integer year;
    private String make;
    private String model;
}
