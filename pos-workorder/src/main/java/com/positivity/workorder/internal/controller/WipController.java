package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.WorkorderStatusDetail;
import com.positivity.workorder.internal.dto.WorkorderStatusView;
import com.positivity.workorder.service.WipService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/workexec/wip")
@RequiredArgsConstructor
@Slf4j
public class WipController {

    private static final String WIP_VIEW_ALL_LOCATIONS = "workorder:wip:view_all_locations";

    private final WipService wipService;

    @Operation(summary = "List active WIP workorders", description = "Returns a paginated list of workorders in active WIP statuses. "
            + "When the caller holds workorder:wip:view_all_locations, results span all locations.")
    @GetMapping
    @PreAuthorize("hasAuthority('workorder:wip:view')")
    @EmitEvent(id = "WORKORDER_WIP_LIST", apiVersion = "1")
    public ResponseEntity<Page<WorkorderStatusView>> listWip(
            @Parameter(description = "Location ID to filter workorders by") @RequestParam String locationId,
            @Parameter(description = "Request cross-location results; requires workorder:wip:view_all_locations") @RequestParam(defaultValue = "false") boolean multiLocation,
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

    @Operation(summary = "Get WIP detail for a workorder", description = "Returns full WIP detail for a single workorder including status history.")
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
        String sanitized = value.toString().replace('\r', '_').replace('\n', '_').replace('\t', '_');
        int length = sanitized.length();
        if (length <= 4) {
            return "****";
        }
        return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
    }
}
