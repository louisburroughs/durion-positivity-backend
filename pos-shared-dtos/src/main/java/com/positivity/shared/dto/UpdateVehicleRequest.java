package com.positivity.shared.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for updating mutable vehicle fields.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateVehicleRequest {

    private String unitNumber;

    private String description;

    private String licensePlate;
    private String licensePlateJurisdiction;
    private Integer year;
    private String make;
    private String model;
    private String trim;
}
