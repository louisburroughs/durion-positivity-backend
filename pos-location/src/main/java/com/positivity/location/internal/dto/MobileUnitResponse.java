package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response payload for mobile unit endpoints.
 *
 * Issue: #76
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response payload describing a mobile unit")
public class MobileUnitResponse {

    @Schema(
            description = "Unique identifier of the mobile unit",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID id;

    @Schema(description = "Display name of the mobile unit", example = "Van 7", requiredMode = NOT_REQUIRED)
    private String name;

    @Schema(
            description = "Identifier of the base location the mobile unit operates from",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID baseLocationId;

    @Schema(description = "Operational status of the mobile unit", example = "ACTIVE", requiredMode = NOT_REQUIRED)
    private String status;

    @Schema(
            description = "Identifier of the travel buffer policy applied to the mobile unit",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private UUID travelBufferPolicyId;

    @Schema(description = "Free-text notes about the mobile unit", example = "Equipped with hydraulic lift", requiredMode = NOT_REQUIRED)
    private String notes;

    @Schema(
            description = "Identifiers of capabilities the mobile unit can perform",
            example = "[\"01960003-0000-7000-8000-000000000010\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> capabilityIds;

    @Schema(
            description = "Timestamp when the mobile unit was created (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "Timestamp when the mobile unit was last updated (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant updatedAt;
}
