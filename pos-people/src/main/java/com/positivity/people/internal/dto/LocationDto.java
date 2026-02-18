package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.LocationType;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder
public class LocationDto {
    UUID locationId;
    String code;
    String displayName;
    LocationType locationType;
    String address;
    String timezone;
    boolean active;
    UUID managerId;
    Instant createdAt;
    Instant updatedAt;
}
