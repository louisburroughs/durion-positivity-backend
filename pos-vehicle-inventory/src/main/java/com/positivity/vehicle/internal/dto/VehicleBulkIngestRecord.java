package com.positivity.vehicle.internal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.Data;

@Data
public class VehicleBulkIngestRecord {

    @NotNull
    private UUID accountId;

    @NotBlank
    @Size(min = 17, max = 17)
    private String vin;

    @NotBlank
    private String unitNumber;

    @NotBlank
    private String description;

    private String licensePlate;
    private String licensePlateJurisdiction;
    private Integer year;
    private String make;
    private String model;
    private String trim;
}
