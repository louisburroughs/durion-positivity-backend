package com.positivity.vehiclefitment.internal.dto;

import lombok.Data;
import org.jspecify.annotations.NonNull;

@Data
public class CreatePartFitmentRequest {

    @NonNull
    private Long partNumberId;

    private String manufacturerName;

    private String makeName;

    private String modelName;

    private String vehicleTypeName;

    private String vehicleYear;

    private String engineType;

    private String submodel;

    private String notes;
}