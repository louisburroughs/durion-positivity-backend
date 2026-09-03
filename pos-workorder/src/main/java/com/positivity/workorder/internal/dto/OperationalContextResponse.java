package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.workorder.internal.enums.ResourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Operational context details for a workorder")
public class OperationalContextResponse {
    @Schema(description = "Operational context version", example = "3", requiredMode = NOT_REQUIRED)
    private String version;

    @Schema(
            description = "Location identifier",
            example = "550e8400-e29b-41d4-a716-446655440300",
            requiredMode = NOT_REQUIRED)
    private UUID locationId;

    /**
     * The workorder's primary assigned resource, named type-neutrally (#1656).
     *
     * <p>This replaces the former {@code bayId}, which was populated from {@code resourceId}
     * regardless of what that id actually pointed at — so a mobile-unit assignment shipped a
     * van's id under a bay-named key that joined to nothing in the dispatch board's
     * {@code bays[]}. The id travels with {@link #resourceType} so a consumer never has to infer
     * the kind of resource from the id itself.
     */
    @Schema(
            description = "Identifier of the workorder's primary assigned resource; read together with "
                    + "resourceType, which says whether it is a bay or a mobile unit",
            example = "550e8400-e29b-41d4-a716-446655440301",
            requiredMode = NOT_REQUIRED)
    private String resourceId;

    @Schema(
            description = "Kind of resource resourceId points at. Null exactly when resourceId is null",
            example = "BAY",
            requiredMode = NOT_REQUIRED)
    private ResourceType resourceType;

    @Schema(description = "Scheduled start time", example = "2026-01-15T09:30:00Z", requiredMode = NOT_REQUIRED)
    private Instant scheduledStartAt;

    @Schema(description = "Scheduled end time", example = "2026-01-15T09:30:00Z", requiredMode = NOT_REQUIRED)
    private Instant scheduledEndAt;

    @Schema(
            description = "Assigned mechanic identifiers",
            example = "[\"550e8400-e29b-41d4-a716-446655440120\"]",
            requiredMode = NOT_REQUIRED)
    private List<UUID> assignedMechanics;

    @Schema(
            description = "Assigned resource identifiers",
            example = "[\"550e8400-e29b-41d4-a716-446655440301\"]",
            requiredMode = NOT_REQUIRED)
    private List<UUID> assignedResources;

    @Schema(
            description = "Operational constraints",
            example = "[\"ALIGNMENT_RACK_REQUIRED\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> constraints;

    @Schema(description = "Whether context is locked after work start", example = "false", requiredMode = REQUIRED)
    private boolean locked; // true once workStartedAt is set
}
