package com.positivity.vehicle.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

import java.util.UUID;

/**
 * Request DTO for creating a vehicle - CAP:091 Story #105.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateVehicleRequest {
    
    @NonNull
    private UUID accountId;
    
    @NonNull
    private String vin;
    
    @NonNull
    private String unitNumber;
    
    @NonNull
    private String description;
    
    private String licensePlate;
    private String licensePlateJurisdiction;
    
    // Optional structured fields
    private Integer year;
    private String make;
    private String model;
    private String trim;
}
