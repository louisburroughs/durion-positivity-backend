package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Flat projection of a descendant location returned by the descendants
 * traversal endpoint.
 *
 * Issue: CAP-214 #655
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Flat projection of a descendant location within a hierarchy traversal")
public class LocationDescendantResponseDTO {

    @Schema(
            description = "Unique identifier of the descendant location",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID id;

    @Schema(description = "Display name of the location", example = "Downtown Service Center", requiredMode = REQUIRED)
    @NotNull
    private String name;

    @Schema(description = "Unique business code of the location", example = "LOC-001", requiredMode = NOT_REQUIRED)
    private String code;

    @Schema(description = "Operational status of the location", example = "ACTIVE", requiredMode = NOT_REQUIRED)
    private String status;

    /** Immediate parent of this location within the traversed chain. */
    @Schema(
            description = "Identifier of the immediate parent of this location within the traversed chain",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID parentId;

    /** Distance from the requested root location (1 = direct child). */
    @Schema(
            description = "Distance from the requested root location (1 = direct child)",
            example = "2",
            requiredMode = REQUIRED)
    private int depth;
}
