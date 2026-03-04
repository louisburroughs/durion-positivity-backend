package com.positivity.customer.internal.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for the {@code VehicleUpdated} workorder event.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class VehicleUpdatedPayload {

    private String vehicleId;
    private String vin;
    private String make;
    private String model;
    private Integer year;
    private String color;
}
