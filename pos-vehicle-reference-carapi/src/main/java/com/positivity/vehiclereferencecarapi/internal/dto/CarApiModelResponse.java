package com.positivity.vehiclereferencecarapi.internal.dto;

import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CarApiModelResponse {
    UUID id;
    UUID modelId;
    UUID makeId;
    String modelName;
}
