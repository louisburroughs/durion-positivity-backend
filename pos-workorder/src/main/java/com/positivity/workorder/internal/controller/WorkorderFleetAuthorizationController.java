package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.workorder.internal.dto.FleetAuthorizationResolutionRequest;
import com.positivity.workorder.internal.dto.FleetAuthorizationView;
import com.positivity.workorder.internal.entity.WorkorderFleetAuthorization;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import com.positivity.workorder.internal.service.FleetAuthorizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fleet payment authorization for a workorder (#1346).
 *
 * <h2>What an advisor uses this for</h2>
 *
 * A request is sent automatically when a fleet-covered workorder is approved. This controller exists
 * for what does not happen automatically: reading the current state, re-requesting after fixing
 * whatever blocked the first attempt, and resolving a refusal — which the domain ruled must always
 * be an explicit human decision, never automatic.
 */
@RestController
@RequestMapping("/v1/workorders/{workorderId}/fleet-authorization")
@RequiredArgsConstructor
@Tag(name = "Workorder Fleet Authorization", description = "Fleet payment authorization for a workorder")
public class WorkorderFleetAuthorizationController {

    private final FleetAuthorizationService fleetAuthorizationService;

    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:fleet_auth:request"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.FLEET_AUTHORIZATION_REQUEST + "')")
    @EmitEvent(id = "WORKORDER_FLEET_AUTHORIZATION_READ", apiVersion = "1")
    @Operation(
            operationId = "getWorkorderFleetAuthorization",
            summary = "Read A Workorder's Fleet Payment Authorization",
            description = """
                    Returns the fleet payment authorization recorded for a workorder, including the vendor's own \
                    reason where it gave one.
                    Use this tool to check why a fleet-covered workorder cannot start, or whether a re-request has \
                    been answered; do not use it to decide whether work may start, because startWorkorder enforces \
                    that itself and will refuse a start this endpoint would not have blocked.
                    Preconditions: none.
                    Required inputs: workorderId path parameter.
                    Emits a WORKORDER_FLEET_AUTHORIZATION_READ event.
                    Returns 200 with the authorization, and 404 when this workorder's payer requires none or none \
                    has ever been requested.
                    """,
            tags = {"Workorder Fleet Authorization"})
    @ApiResponse(responseCode = "200", description = "The recorded authorization")
    @ApiResponse(
            responseCode = "404",
            description = "No fleet payment authorization is recorded for this workorder",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<FleetAuthorizationView> getWorkorderFleetAuthorization(
            @Parameter(description = "Workorder the authorization concerns", required = true) @PathVariable
                    UUID workorderId) {
        return fleetAuthorizationService
                .find(workorderId)
                .map(FleetAuthorizationView::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/requests")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:fleet_auth:request"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.FLEET_AUTHORIZATION_REQUEST + "')")
    @EmitEvent(id = "WORKORDER_FLEET_AUTHORIZATION_REQUEST", apiVersion = "1")
    @Operation(
            operationId = "requestWorkorderFleetAuthorization",
            summary = "Request Or Re-Request Fleet Payment Authorization",
            description = """
                    Asks this workorder's payer's fleet program to authorize paying for it. The same endpoint \
                    that fires automatically on approval; calling it again is how an advisor retries after fixing \
                    whatever blocked the first attempt, such as a missing vehicle identity.
                    Use this tool to retry a stuck authorization; do not call it while one is already PENDING or \
                    GRANTED, because it will not re-ask in either case and asking a fleet twice for the same \
                    workorder risks opening a second authorization at the vendor.
                    Preconditions: this workorder's payer must require fleet authorization; there is nothing to \
                    request otherwise.
                    Required inputs: workorderId path parameter.
                    Emits a WORKORDER_FLEET_AUTHORIZATION_REQUEST event, and where a vehicle can be identified, a \
                    workorder.fleetauth.requested command for pos-supplier to act on.
                    Returns 200 with the authorization, whose status is PENDING when the vendor was asked, or \
                    MANUAL_REVIEW when no vehicle could be identified so nobody was asked; and 404 when this \
                    workorder's payer does not require fleet authorization.
                    """,
            tags = {"Workorder Fleet Authorization"})
    @ApiResponse(responseCode = "200", description = "The authorization as it now stands")
    @ApiResponse(
            responseCode = "404",
            description = "This workorder's payer does not require fleet authorization",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<FleetAuthorizationView> requestWorkorderFleetAuthorization(
            @Parameter(description = "Workorder to request authorization for", required = true) @PathVariable
                    UUID workorderId) {
        return fleetAuthorizationService
                .requestAuthorization(workorderId)
                .map(FleetAuthorizationView::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/resolution")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:fleet_auth:resolve"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.FLEET_AUTHORIZATION_RESOLVE + "')")
    @EmitEvent(id = "WORKORDER_FLEET_AUTHORIZATION_RESOLVE", apiVersion = "1")
    @Operation(
            operationId = "resolveWorkorderFleetAuthorization",
            summary = "Resolve A Refused Fleet Payment Authorization",
            description = """
                    Records what an advisor decided to do about a fleet payment authorization that did not come \
                    back granted: revise the scope and try again, or cancel and find another payer.
                    Use this tool after a fleet program refuses payment or no answer could be obtained; do not use \
                    it to select a different payer directly, because that needs a payer model this endpoint does \
                    not have -- cancel here and record the new payer through whatever assigns one.
                    Preconditions: an authorization must be recorded for this workorder and must not already be \
                    GRANTED.
                    Required inputs: workorderId path parameter; resolution (SCOPE_REVISED or CANCELLED) and \
                    reason in the request body.
                    Emits a WORKORDER_FLEET_AUTHORIZATION_RESOLVE event.
                    Returns 200 with the authorization, 404 when none is recorded for this workorder, and 409 \
                    when the recorded authorization is already GRANTED and needs no resolution.
                    """,
            tags = {"Workorder Fleet Authorization"})
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "What the advisor decided to do about the refusal, and why. Do not use this to select a "
                    + "different payer directly, because that needs a payer model this endpoint does not "
                    + "have -- use CANCELLED here and record the new payer through whatever assigns one "
                    + "instead.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = FleetAuthorizationResolutionRequest.class),
                            examples =
                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                            name = "Scope revised to fit fleet coverage",
                                            value = """
                                                    {
                                                      "resolution": "SCOPE_REVISED",
                                                      "reason": "Customer approved a reduced scope the fleet will cover"
                                                    }
                                                    """)))
    @ApiResponse(responseCode = "200", description = "The resolved authorization")
    @ApiResponse(
            responseCode = "404",
            description = "No fleet payment authorization is recorded for this workorder",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The recorded authorization is already GRANTED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<FleetAuthorizationView> resolveWorkorderFleetAuthorization(
            @Parameter(description = "Workorder whose authorization is being resolved", required = true) @PathVariable
                    UUID workorderId,
            @Valid @RequestBody FleetAuthorizationResolutionRequest request,
            Authentication authentication) {
        WorkorderFleetAuthorization resolved = fleetAuthorizationService.resolve(
                workorderId, request.resolution(), request.reason(), authentication.getName());
        return ResponseEntity.ok(FleetAuthorizationView.from(resolved));
    }
}
