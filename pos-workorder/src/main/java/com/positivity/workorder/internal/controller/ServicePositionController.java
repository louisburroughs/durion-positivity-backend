package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import com.positivity.workorder.internal.dto.AssignServicePositionRequest;
import com.positivity.workorder.internal.dto.ServicePositionResponse;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import com.positivity.workorder.internal.service.ServicePositionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Service Position API", description = "Assign, change and release the position a workorder occupies")
@RestController
@RequestMapping("/v1/workorders")
@RequiredArgsConstructor
@Slf4j
public class ServicePositionController {

    private static final String SYSTEM_USERNAME = "system";

    private final ServicePositionService servicePositionService;

    @PutMapping("/{workorderId}/position")
    @EmitEvent(id = "WORKORDER_POSITION_ASSIGN", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:position:assign"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.POSITION_ASSIGN + "')")
    @Operation(
            operationId = "assignServicePosition",
            summary = "Assign or Change a Workorder's Service Position",
            description = """
                    Places the workorder on a service position — a bay, a mobile unit, or the site's HOLD \
                    (parking) position — recording who placed it, when and why, and releasing whatever \
                    position it held before in the same transaction.
                    Use this tool to dispatch a workorder to a bay, move it between bays or mobile units, or \
                    park it; do not use releaseServicePosition, which leaves the workorder unplaced, and do not \
                    use overrideOperationalContext, which is the manager exception path and also rewrites \
                    mechanics and location.
                    Preconditions: the workorder must exist and must not be COMPLETED or CANCELLED; a BAY or \
                    MOBILE_UNIT must be known to this module's location replicas, belong to the workorder's own \
                    site and hold no other open workorder, while HOLD has no capacity limit and accepts only \
                    the workorder's own locationId; and a caller whose workorder:position:assign grant is \
                    location-scoped must have the workorder's shop within reach (ADR-0061).
                    Required inputs: workorderId (UUID) as a path parameter and a body with resourceType \
                    (BAY, MOBILE_UNIT or HOLD, required); resourceId is required for BAY and MOBILE_UNIT and \
                    optional for HOLD, reason is optional, and re-sending the placement already in force is a \
                    no-op that writes no history.
                    Emits a WORKORDER_POSITION_ASSIGN event and marks the workorder fact changed.
                    Returns 403 when the caller's location scope does not cover the workorder's shop, 404 when \
                    no workorder exists for the id, 409 RESOURCE_OCCUPIED when the position already holds \
                    another open workorder, 409 WORKORDER_CLOSED when the workorder is COMPLETED or CANCELLED, \
                    422 SERVICE_POSITION_INVALID when the position is unknown or at another site, and 422 \
                    SERVICE_POSITION_INACTIVE when the bay is out of service or the mobile unit is not deployed.
                    Placing an APPROVED workorder that already has a technician on a BAY or MOBILE_UNIT moves it \
                    to ASSIGNED; a HOLD does not, because it is a parking space rather than somewhere work \
                    happens.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Position assigned",
            content = @Content(schema = @Schema(implementation = ServicePositionResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller's location scope does not cover the workorder's shop (ApiError.code "
                    + "LOCATION_SCOPE_DENIED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Workorder not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Position already holds another open workorder (ApiError.code RESOURCE_OCCUPIED, with "
                    + "the occupying workorder id as referenceId), or the workorder is closed (ApiError.code "
                    + "WORKORDER_CLOSED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Unknown position, or one belonging to another site (ApiError.code "
                    + "SERVICE_POSITION_INVALID), or a bay or mobile unit that is not active (ApiError.code "
                    + "SERVICE_POSITION_INACTIVE)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ServicePositionResponse> assignServicePosition(
            @Parameter(description = "ID of the workorder", example = "550e8400-e29b-41d4-a716-446655440001")
                    @PathVariable
                    UUID workorderId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The position to place the workorder on, and why.",
                            required = true,
                            content =
                                    @Content(
                                            schema = @Schema(implementation = AssignServicePositionRequest.class),
                                            examples = {
                                                @ExampleObject(
                                                        name = "Dispatch to a bay",
                                                        value =
                                                                "{\"resourceType\":\"BAY\",\"resourceId\":\"550e8400-e29b-41d4-a716-446655440301\",\"reason\":\"Alignment rack required\"}"),
                                                @ExampleObject(
                                                        name = "Park the vehicle",
                                                        value =
                                                                "{\"resourceType\":\"HOLD\",\"reason\":\"Awaiting parts\"}")
                                            }))
                    @Valid
                    @RequestBody
                    AssignServicePositionRequest request) {
        return ResponseEntity.ok(servicePositionService.assignPosition(workorderId, request, resolveActor()));
    }

    @DeleteMapping("/{workorderId}/position")
    @EmitEvent(id = "WORKORDER_POSITION_RELEASE", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:position:assign"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.POSITION_ASSIGN + "')")
    @Operation(
            operationId = "releaseServicePosition",
            summary = "Release a Workorder's Service Position",
            description = """
                    Gives up the position the workorder holds, leaving it deliberately unplaced, freeing the \
                    bay or mobile unit for another job, and closing the history row with the reason rather \
                    than deleting it.
                    Use this tool when a workorder leaves a position without going to another one; do not use \
                    assignServicePosition, which moves it to a named position instead.
                    Preconditions: the workorder must exist and must not be COMPLETED or CANCELLED, and \
                    releasing a workorder that holds no position succeeds and writes nothing, so the call is \
                    idempotent; completing or cancelling a workorder releases its position on its own, leaving \
                    this endpoint for releasing one while the job is still open.
                    Required inputs: workorderId (UUID) as a path parameter; reason is an optional query \
                    parameter recorded on the closed history row.
                    Emits a WORKORDER_POSITION_RELEASE event and marks the workorder fact changed.
                    Releasing the position of an ASSIGNED workorder moves it back to APPROVED: ASSIGNED means \
                    a technician and a bay or mobile unit, and it now has nowhere to be worked.
                    Returns 403 when the caller's location scope does not cover the workorder's shop, 404 when \
                    no workorder exists for the id, and 409 WORKORDER_CLOSED when the workorder is COMPLETED or \
                    CANCELLED.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Position released, or the workorder already held none",
            content = @Content(schema = @Schema(implementation = ServicePositionResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller's location scope does not cover the workorder's shop (ApiError.code "
                    + "LOCATION_SCOPE_DENIED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Workorder not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Workorder is COMPLETED or CANCELLED (ApiError.code WORKORDER_CLOSED)",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ServicePositionResponse> releaseServicePosition(
            @Parameter(description = "ID of the workorder", example = "550e8400-e29b-41d4-a716-446655440001")
                    @PathVariable
                    UUID workorderId,
            @Parameter(description = "Why the position is being given up", example = "Vehicle moved to the lot")
                    @RequestParam(value = "reason", required = false)
                    String reason) {
        return ResponseEntity.ok(servicePositionService.releasePosition(workorderId, resolveActor(), reason));
    }

    @GetMapping("/{workorderId}/position")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:workorder:view"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.WORKORDER_VIEW + "')")
    @Operation(
            operationId = "getServicePosition",
            summary = "Get a Workorder's Service Position and Technician",
            description = """
                    Returns where the workorder is and who is working on it together — the current service \
                    position, the current technician, the workorder status, and the full position history \
                    newest first.
                    Use this tool to read dispatch state for one workorder; do not use assignServicePosition or \
                    releaseServicePosition, which change it. getOperationalContext answers the position half \
                    only and carries no history.
                    Preconditions: the workorder must exist. A workorder with no position returns null \
                    resourceType and resourceId, and a workorder with no technician returns a null \
                    technicianId; neither is an error.
                    Required inputs: workorderId (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no workorder exists for the id.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Current position and technician returned",
            content = @Content(schema = @Schema(implementation = ServicePositionResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Workorder not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ServicePositionResponse> getServicePosition(
            @Parameter(description = "ID of the workorder", example = "550e8400-e29b-41d4-a716-446655440001")
                    @PathVariable
                    UUID workorderId) {
        return ResponseEntity.ok(servicePositionService.getPosition(workorderId));
    }

    /**
     * The acting user, as the technician endpoints resolve it: the security context wins over
     * anything the body could claim, and a JSON-quoted subject is unwrapped.
     */
    @NonNull
    private String resolveActor() {
        String username = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_USERNAME);
        if (username.length() >= 2 && username.startsWith("\"") && username.endsWith("\"")) {
            return username.substring(1, username.length() - 1);
        }
        return username;
    }
}
