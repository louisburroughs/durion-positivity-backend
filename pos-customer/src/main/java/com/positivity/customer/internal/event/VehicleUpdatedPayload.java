package com.positivity.customer.internal.event;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    @JsonProperty("vehicleId")
    @JsonAlias({"vehicle_id", "vehicleID", "id"})
    private String vehicleId;

    @JsonProperty("vin")
    @JsonAlias({"vin_normalized", "vinNormalized"})
    private String vin;

    @JsonProperty("make")
    @JsonAlias({"vehicle_make", "vehicleMake"})
    private String make;

    @JsonProperty("model")
    @JsonAlias({"vehicle_model", "vehicleModel"})
    private String model;

    @JsonProperty("year")
    @JsonAlias({"model_year", "vehicle_year", "modelYear", "vehicleYear"})
    private Integer year;

    @JsonProperty("color")
    @JsonAlias({"vehicle_color", "vehicleColor"})
    private String color;
}
