package com.positivity.vehicle.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for upserting vehicle care preferences.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request DTO for creating or replacing vehicle care preferences")
public class UpsertPreferencesRequest {

    @Schema(
            description = "Identifier of the vehicle the preferences apply to",
            requiredMode = REQUIRED,
            example = "01960003-0000-7000-8000-000000000001")
    @NotNull
    private UUID vehicleId;

    @Schema(
            description = "Preferences payload stored as flexible key/value JSON",
            requiredMode = REQUIRED,
            example = "{\"oilType\":\"synthetic\",\"washPreference\":\"hand\"}")
    @NotNull
    private Map<String, Object> preferences;

    @Schema(
            description = "Optional service notes for technicians",
            example = "Customer prefers synthetic oil only",
            requiredMode = NOT_REQUIRED)
    private String serviceNotes;

    @Schema(
            description = "User identifier that created the preference record",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID createdByUserId;

    @Schema(
            description = "User identifier that last updated the preference record",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private UUID updatedByUserId;
}
