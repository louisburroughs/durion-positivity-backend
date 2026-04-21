package com.positivity.vehiclefitment.service.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

@Data
@NoArgsConstructor
public class CreatePartFitmentRequest {

    public CreatePartFitmentRequest(@NonNull Long partNumberId) {
        this.partNumberId = partNumberId;
    }

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
