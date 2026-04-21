package com.positivity.vehiclefitment.internal.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartFitmentResponse {

    private UUID id;

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