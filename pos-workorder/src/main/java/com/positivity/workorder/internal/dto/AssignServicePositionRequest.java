package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.workorder.internal.enums.ResourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request to place a workorder on a service position, or to move it to another one (#1983). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Service position to place the workorder on")
public class AssignServicePositionRequest {

    @NotNull(message = "resourceType is required")
    @Schema(
            description = "Kind of position: BAY and MOBILE_UNIT are pos-location resources and hold one open "
                    + "workorder each; HOLD is the site's parking lot and holds any number",
            example = "BAY",
            requiredMode = REQUIRED)
    private ResourceType resourceType;

    /**
     * The position itself.
     *
     * <p>Required for {@link ResourceType#BAY} and {@link ResourceType#MOBILE_UNIT}, where it names
     * a resource that must exist in this module's {@code ext_bay} / {@code ext_mobile_unit}
     * replicas and belong to the workorder's site. Optional for {@link ResourceType#HOLD}, whose
     * only legal value is the workorder's own {@code locationId} — omitting it means "park it here"
     * and the server fills the site in; supplying a different site is a 422, because a vehicle
     * cannot be parked in another shop's lot.
     */
    @Schema(
            description = "Bay or mobile-unit id. Optional for HOLD, which parks the workorder at its own site; "
                    + "for HOLD the only accepted value is that site's locationId",
            example = "550e8400-e29b-41d4-a716-446655440301",
            requiredMode = NOT_REQUIRED)
    private UUID resourceId;

    @Schema(
            description = "Why the workorder is being placed here; recorded on the position history",
            example = "Alignment rack required",
            requiredMode = NOT_REQUIRED)
    private String reason;
}
