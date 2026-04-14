package com.positivity.vehicle.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
            description = "Vehicle identifier",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID vehicleId;

    @Schema(
            description = "Preferences payload stored as flexible key/value JSON",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Map<String, Object> preferences;

    @Schema(description = "Optional service notes for technicians", example = "Customer prefers synthetic oil only")
    private String serviceNotes;

    @Schema(
            description = "User identifier that created the preference record",
            example = "550e8400-e29b-41d4-a716-446655440001")
    private UUID createdByUserId;

    @Schema(
            description = "User identifier that last updated the preference record",
            example = "550e8400-e29b-41d4-a716-446655440002")
    private UUID updatedByUserId;
}
