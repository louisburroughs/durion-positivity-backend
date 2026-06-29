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
 * Response payload for service area endpoints.
 *
 * Issue: #76
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response payload describing a service area")
public class ServiceAreaResponse {

    @Schema(
            description = "Unique identifier of the service area",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID id;

    @Schema(description = "Display name of the service area", example = "North Metro", requiredMode = NOT_REQUIRED)
    private String name;

    @Schema(
            description = "Description of the service area",
            example = "Northern metropolitan coverage zone",
            requiredMode = NOT_REQUIRED)
    private String description;

    @Schema(description = "Whether the service area is active", example = "true", requiredMode = NOT_REQUIRED)
    private Boolean active;

    @Schema(description = "Postal codes included in the service area", requiredMode = NOT_REQUIRED)
    private List<ServiceAreaRequest.PostalCodeEntry> postalCodes;

    @Schema(
            description = "Timestamp when the service area was created (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "Timestamp when the service area was last updated (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant updatedAt;
}
