package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScope.Reach;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import com.positivity.workorder.internal.dto.WorkorderStatusDetail;
import com.positivity.workorder.internal.dto.WorkorderStatusView;
import com.positivity.workorder.internal.exception.WorkorderRequestValidationException;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import com.positivity.workorder.internal.service.LocationHierarchyService;
import com.positivity.workorder.internal.service.WipService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for Work In Progress (WIP) status visibility.
 *
 * <p>
 * Provides a paginated dashboard of active workorders and a detail view
 * for a single workorder. Multi-location access is controlled by the
 * {@code workorder:wip:view_all_locations} permission.
 *
 * <p>
 * Single-location access is additionally subject to the caller's location scope (ADR-0061 §3,
 * #1871): {@code @PreAuthorize} answers "may this caller view WIP", and
 * {@link LocationScope#require} answers "…at this location". A caller whose
 * {@code workorder:wip:view} grant is location-scoped can no longer read another shop's board by
 * changing the {@code locationId} query parameter, nor by addressing one of its workorders by id.
 *
 * <p>
 * The cross-location board (#1872) is narrowed the same way: a holder of
 * {@code workorder:wip:view_all_locations} whose grant is itself location-scoped sees every shop
 * within that grant's reach rather than every shop there is; an unscoped holder is unchanged.
 */
@Tag(name = "WIP Dashboard", description = "Endpoints for Work-In-Progress status visibility")
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"workorder:wip:view"})
@RequestMapping("/v1/workexec/wip")
@RequiredArgsConstructor
@Slf4j
public class WipController {

    private static final String WIP_VIEW_ALL_LOCATIONS = "workorder:wip:view_all_locations";

    private static final String LOCATION_SCOPE_DENIED_DESCRIPTION =
            "Caller holds workorder:wip:view but its location scope does not cover the requested location"
                    + " (ApiError.code LOCATION_SCOPE_DENIED, see docs/ERROR_ENVELOPE.md)";

    private final WipService wipService;
    private final LocationHierarchyService locationHierarchyService;

    @Operation(operationId = "listWipWorkorders", summary = "List Active WIP Workorders", description = """
                    Returns a page of workorders in active work-in-progress statuses (APPROVED, ASSIGNED, \
                    WORK_IN_PROGRESS, AWAITING_PARTS, AWAITING_APPROVAL), enriched with customer and vehicle \
                    references.
                    Use this tool for the WIP status board; do not use getDispatchDashboard, which aggregates \
                    mechanics, bays, and conflicts for one date, and use getWipDetail for a single workorder's \
                    status history.
                    Preconditions: multiLocation=true requires the caller to hold \
                    workorder:wip:view_all_locations, and when that grant is itself location-scoped the page \
                    is narrowed to the shops within its reach (an empty reach is an empty page); otherwise \
                    results are scoped to the given location, and a caller whose workorder:wip:view grant is \
                    location-scoped must have that location within reach (ADR-0061).
                    Required inputs: locationId (UUID as a string) as a query parameter — ignored when \
                    multiLocation is true; multiLocation defaults to false and page size defaults to 25.
                    Emits a WORKORDER_WIP_LIST audit event; no workorder state changes — this is a read-only \
                    projection.
                    Returns 400 when locationId does not parse as a UUID, 403 FORBIDDEN when multiLocation is \
                    requested without workorder:wip:view_all_locations, and 403 LOCATION_SCOPE_DENIED when the \
                    caller's location scope does not cover locationId.
                    """)
    @ApiResponse(responseCode = "200", description = "Page of active WIP workorders")
    @ApiResponse(
            responseCode = "400",
            description = "locationId does not parse as a UUID",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Caller lacks workorder:wip:view_all_locations for multiLocation=true (ApiError.code"
                    + " FORBIDDEN), or " + LOCATION_SCOPE_DENIED_DESCRIPTION,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.WIP_VIEW + "')")
    @EmitEvent(id = "WORKORDER_WIP_LIST", apiVersion = "1")
    public ResponseEntity<Page<WorkorderStatusView>> listWip(
            @Parameter(description = "Location ID to filter workorders by") @RequestParam String locationId,
            @Parameter(
                            description = "Request cross-location results; requires workorder:wip:view_all_locations",
                            schema = @Schema(type = "boolean", defaultValue = "false"))
                    @RequestParam(defaultValue = "false")
                    boolean multiLocation,
            @ParameterObject @PageableDefault(size = 25) Pageable pageable,
            Authentication authentication) {

        if (multiLocation) {
            // Widening is gated by a separate, deliberately rare permission.
            if (authentication.getAuthorities().stream()
                    .noneMatch(a -> WIP_VIEW_ALL_LOCATIONS.equals(a.getAuthority()))) {
                throw new AccessDeniedException("Missing required permission: " + WIP_VIEW_ALL_LOCATIONS);
            }
            // #1872: a LOCATION-scoped holder of view_all_locations is narrowed to that grant's reach
            // rather than shown every shop. An unscoped holder (ADMIN, reach ALL) keeps the full board.
            Optional<Reach> reach = SecurityContextHelper.locationScope().reach(WIP_VIEW_ALL_LOCATIONS);
            if (reach.isPresent()) {
                Set<UUID> reachable = locationHierarchyService.reachableLocations(reach.get());
                log.debug("WIP multi-location list narrowed to reach: shops={}", reachable.size());
                return ResponseEntity.ok(wipService.getWipWorkordersAtShops(reachable, pageable));
            }
        } else {
            // Validate before the scope check so a malformed id is a 400 for every caller rather than
            // a 403 for scoped callers only; WipService re-parses, which keeps its query shape intact.
            UUID requestedLocation = parseLocationId(locationId);
            SecurityContextHelper.locationScope().require(WorkorderPermissions.WIP_VIEW, requestedLocation);
        }

        log.debug("WIP list requested: locationId(mask)={}, multiLocation={}", maskForLog(locationId), multiLocation);

        Page<WorkorderStatusView> result = wipService.getWipWorkorders(locationId, multiLocation, pageable);
        return ResponseEntity.ok(result);
    }

    @Operation(operationId = "getWipDetail", summary = "Get WIP Detail for Workorder", description = """
                    Returns the full work-in-progress detail for one workorder: current status, the complete \
                    status transition history with actors and reasons, blocking part numbers, and the currently \
                    assigned technician.
                    Use this tool when drilling into a single workorder from the WIP board; use listWipWorkorders \
                    instead for the paginated board itself.
                    Preconditions: the workorder must exist; it does not need to be in an active WIP status to be \
                    viewed. A caller whose workorder:wip:view grant is location-scoped must have the workorder's \
                    location within reach (ADR-0061).
                    Required inputs: workorderId (UUID) as a path parameter.
                    Emits a WORKORDER_WIP_VIEW audit event; no workorder state changes — this is a read-only \
                    projection.
                    Returns 404 when no workorder exists for the id, and 403 LOCATION_SCOPE_DENIED when the \
                    workorder exists but its location is outside the caller's scope.
                    """)
    @ApiResponse(responseCode = "200", description = "WIP detail retrieved successfully")
    @ApiResponse(
            responseCode = "403",
            description = LOCATION_SCOPE_DENIED_DESCRIPTION,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Workorder not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{workorderId}")
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.WIP_VIEW + "')")
    @EmitEvent(id = "WORKORDER_WIP_VIEW", apiVersion = "1")
    public ResponseEntity<WorkorderStatusDetail> getWipDetail(
            @Parameter(description = "UUID of the workorder") @PathVariable UUID workorderId) {

        log.debug("WIP detail requested: workorderId(mask)={}", maskForLog(workorderId));

        // Existence first (404 from the service), then scope: a 403 for an id that does not exist
        // would let a caller probe which workorder ids are real. The detail's locationId is the
        // workorder's shop; a workorder without one answers "" which a scoped caller cannot cover.
        WorkorderStatusDetail detail = wipService.getWipDetail(workorderId);
        String workorderLocation = Objects.requireNonNullElse(detail.getLocationId(), "");
        SecurityContextHelper.locationScope().require(WorkorderPermissions.WIP_VIEW, workorderLocation);
        return ResponseEntity.ok(detail);
    }

    private static @NonNull UUID parseLocationId(@NonNull String locationId) {
        try {
            return UUID.fromString(locationId);
        } catch (IllegalArgumentException e) {
            throw new WorkorderRequestValidationException("locationId is not a valid UUID: " + locationId);
        }
    }

    private String maskForLog(Object value) {
        if (value == null) {
            return "null";
        }
        String sanitized =
                value.toString().replace('\r', '_').replace('\n', '_').replace('\t', '_');
        int length = sanitized.length();
        if (length <= 4) {
            return "****";
        }
        return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
    }
}
