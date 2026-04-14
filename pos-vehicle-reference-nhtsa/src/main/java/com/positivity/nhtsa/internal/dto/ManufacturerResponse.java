package com.positivity.nhtsa.internal.dto;

import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ManufacturerResponse {
    UUID id;
    String name;
}
