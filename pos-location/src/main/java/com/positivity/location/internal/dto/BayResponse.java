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
 * Response payload for bay endpoints.
 *
 * Issue: CAP-136 #77
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response payload describing a service bay")
public class BayResponse {

    @Schema(
            description = "Unique identifier of the bay",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID id;

    @Schema(
            description = "Identifier of the location that owns the bay",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID locationId;

    @Schema(description = "Display name of the bay", example = "Bay A1", requiredMode = REQUIRED)
    @NotNull
    private String name;

    @Schema(description = "Type classification of the bay", example = "LIFT", requiredMode = NOT_REQUIRED)
    private String bayType;

    @Schema(description = "Operational status of the bay", example = "ACTIVE", requiredMode = NOT_REQUIRED)
    private String status;

    @Schema(
            description = "Maximum number of vehicles that can be serviced concurrently in the bay",
            example = "2",
            requiredMode = NOT_REQUIRED)
    private Integer maxConcurrentVehicles;

    @Schema(
            description = "Identifiers of service capabilities supported by the bay",
            example = "[\"01960003-0000-7000-8000-000000000010\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> serviceCapabilityIds;

    @Schema(
            description = "Identifiers of skills required to operate the bay",
            example = "[\"01960003-0000-7000-8000-000000000020\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> skillRequirementIds;

    @Schema(
            description = "Timestamp when the bay was created (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "Timestamp when the bay was last modified (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant lastModifiedAt;
}
