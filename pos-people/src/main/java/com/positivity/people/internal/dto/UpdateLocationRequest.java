package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.LocationType;
import lombok.Data;

import java.util.UUID;

@Data
public class UpdateLocationRequest {
    private String displayName;
    private LocationType locationType;
    private String address;
    private String timezone;
    private Boolean active;
    private UUID managerId;
}
