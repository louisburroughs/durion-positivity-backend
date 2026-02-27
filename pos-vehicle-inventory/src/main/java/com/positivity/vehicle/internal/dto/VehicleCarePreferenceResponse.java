package com.positivity.vehicle.internal.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class VehicleCarePreferenceResponse {
    UUID id;
    UUID vehicleId;
    Map<String, Object> preferences;
    String serviceNotes;
    UUID createdByUserId;
    UUID updatedByUserId;
    Instant createdAt;
    Instant updatedAt;
    Long version;
}
