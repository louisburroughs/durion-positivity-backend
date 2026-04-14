package com.positivity.vehiclereferencecarapi.internal.dto;

import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CarApiMakeResponse {
    UUID id;
    UUID makeId;
    String makeName;
}
