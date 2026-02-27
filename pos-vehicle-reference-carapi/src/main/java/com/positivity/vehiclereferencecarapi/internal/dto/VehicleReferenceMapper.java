package com.positivity.vehiclereferencecarapi.internal.dto;

import com.positivity.vehiclereferencecarapi.internal.entity.CarApiMake;
import com.positivity.vehiclereferencecarapi.internal.entity.CarApiModel;

public final class VehicleReferenceMapper {
    private VehicleReferenceMapper() {
    }

    public static CarApiMakeResponse toMakeResponse(CarApiMake make) {
        return CarApiMakeResponse.builder()
                .id(make.getId())
                .makeId(make.getMakeId())
                .makeName(make.getMakeName())
                .build();
    }

    public static CarApiModelResponse toModelResponse(CarApiModel model) {
        return CarApiModelResponse.builder()
                .id(model.getId())
                .modelId(model.getModelId())
                .makeId(model.getMakeId())
                .modelName(model.getModelName())
                .build();
    }
}
