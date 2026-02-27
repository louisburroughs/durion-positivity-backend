package com.positivity.nhtsa.internal.dto;

import java.util.UUID;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class MakeResponse {
    UUID id;
    String name;
    UUID manufacturerId;
}
