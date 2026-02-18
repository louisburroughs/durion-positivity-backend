package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.LocationType;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.UUID;

@Data
public class CreateLocationRequest {

    @NotBlank(message = "code is required")
    private String code;

    @NotBlank(message = "displayName is required")
    private String displayName;

    private LocationType locationType = LocationType.STORE;

    private String address;

    private String timezone;

    private UUID managerId;
}
