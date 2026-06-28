package com.positivity.vehicle.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Vehicle care preferences record")
public class VehicleCarePreferenceResponse {

    @Schema(
            description = "Unique identifier of the preference record",
            example = "01960003-0000-7000-8000-000000000010",
            requiredMode = REQUIRED)
    UUID id;

    @Schema(
            description = "Identifier of the vehicle the preferences apply to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    UUID vehicleId;

    @Schema(
            description = "Preferences payload stored as flexible key/value JSON",
            example = "{\"oilType\":\"synthetic\",\"washPreference\":\"hand\"}",
            requiredMode = REQUIRED)
    Map<String, Object> preferences;

    @Schema(
            description = "Service notes for technicians",
            example = "Customer prefers synthetic oil only",
            requiredMode = NOT_REQUIRED)
    String serviceNotes;

    @Schema(
            description = "User identifier that created the preference record",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    UUID createdByUserId;

    @Schema(
            description = "User identifier that last updated the preference record",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    UUID updatedByUserId;

    @Schema(
            description = "Timestamp when the preference record was created",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    Instant createdAt;

    @Schema(
            description = "Timestamp when the preference record was last updated",
            example = "2026-01-16T14:45:00Z",
            requiredMode = REQUIRED)
    Instant updatedAt;

    @Schema(description = "Optimistic-locking version of the preference record", example = "1", requiredMode = REQUIRED)
    Long version;
}
