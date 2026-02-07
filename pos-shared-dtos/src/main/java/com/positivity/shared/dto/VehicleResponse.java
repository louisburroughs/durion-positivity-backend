package com.positivity.shared.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for vehicle operations - CAP:091.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VehicleResponse {

    private UUID vehicleId;
    private UUID accountId;
    private String vin;
    private String vinNormalized;
    private String unitNumber;
    private String description;
    private String licensePlate;
    private String licensePlateJurisdiction;
    private Integer year;
    private String make;
    private String model;
    private String trim;
    private Boolean isActive;
    private Instant createdAt;
    private Instant updatedAt;
    private Long version;
}
