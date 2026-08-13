package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.WorkorderStatusDetail;
import com.positivity.workorder.internal.dto.WorkorderStatusView;
import com.positivity.workorder.service.WipService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final WipService wipService;

    @Operation(operationId = "listWipWorkorders", summary = "List Active WIP Workorders", description = """
                    Returns a page of workorders in active work-in-progress statuses (APPROVED, ASSIGNED, \
                    WORK_IN_PROGRESS, AWAITING_PARTS, AWAITING_APPROVAL), enriched with customer and vehicle \
                    references.
                    Use this tool for the WIP status board; do not use getDispatchDashboard, which aggregates \
                    mechanics, bays, and conflicts for one date, and use getWipDetail for a single workorder's \
                    status history.
                    Preconditions: multiLocation=true requires the caller to hold \
                    workorder:wip:view_all_locations; otherwise results are scoped to the given location.
                    Required inputs: locationId (UUID as a string) as a query parameter — ignored when \
                    multiLocation is true; multiLocation defaults to false and page size defaults to 25.
                    Emits a WORKORDER_WIP_LIST audit event; no workorder state changes — this is a read-only \
                    projection.
                    Returns 400 when locationId does not parse as a UUID, and 403 when multiLocation is \
                    requested without workorder:wip:view_all_locations.
                    """)
    @GetMapping
    @PreAuthorize("hasAuthority('workorder:wip:view')")
    @EmitEvent(id = "WORKORDER_WIP_LIST", apiVersion = "1")
    public ResponseEntity<Page<WorkorderStatusView>> listWip(
            @Parameter(description = "Location ID to filter workorders by") @RequestParam String locationId,
            @Parameter(
                            description = "Request cross-location results; requires workorder:wip:view_all_locations",
                            schema = @Schema(type = "boolean", defaultValue = "false"))
                    @RequestParam(defaultValue = "false")
                    boolean multiLocation,
            @PageableDefault(size = 25) Pageable pageable,
            Authentication authentication) {

        if (multiLocation
                && authentication.getAuthorities().stream()
                        .noneMatch(a -> WIP_VIEW_ALL_LOCATIONS.equals(a.getAuthority()))) {
            throw new AccessDeniedException("Missing required permission: " + WIP_VIEW_ALL_LOCATIONS);
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
                    viewed.
                    Required inputs: workorderId (UUID) as a path parameter.
                    Emits a WORKORDER_WIP_VIEW audit event; no workorder state changes — this is a read-only \
                    projection.
                    Returns 400 with code INVALID_ARGUMENT when no workorder exists for the id — the not-found \
                    case surfaces as 400 rather than 404 in this operation.
                    """)
    @GetMapping("/{workorderId}")
    @PreAuthorize("hasAuthority('workorder:wip:view')")
    @EmitEvent(id = "WORKORDER_WIP_VIEW", apiVersion = "1")
    public ResponseEntity<WorkorderStatusDetail> getWipDetail(
            @Parameter(description = "UUID of the workorder") @PathVariable UUID workorderId) {

        log.debug("WIP detail requested: workorderId(mask)={}", maskForLog(workorderId));

        WorkorderStatusDetail detail = wipService.getWipDetail(workorderId);
        return ResponseEntity.ok(detail);
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
