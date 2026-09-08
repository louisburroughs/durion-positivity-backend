package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScope.Reach;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import com.positivity.workorder.internal.dto.LaborIntelligenceRow;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import com.positivity.workorder.internal.service.LaborIntelligenceService;
import com.positivity.workorder.internal.service.LocationHierarchyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Durion labor intelligence (#1575 Tier 0 "historical actual repair times" / Tier 4 sketch,
 * T0-5). A read-only curation report over work the shop has already finished; nothing here
 * writes to pos-catalog, and no suggestion is ever promoted automatically.
 */
@Tag(
        name = "Labor Intelligence",
        description = "What a shop's own finished work says about each operation: median actual time against"
                + " the guide baseline it was quoted at, with technician medians and a suggested"
                + " standard once the sample is deep enough.")
@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/v1/workorders/labor-intelligence")
public class LaborIntelligenceController {

    private static final String LOCATION_SCOPE_DENIED_DESCRIPTION =
            "Caller holds workorder:labor_intelligence:view but its location scope does not cover the requested"
                    + " location (ApiError.code LOCATION_SCOPE_DENIED, see docs/ERROR_ENVELOPE.md)";

    private final LaborIntelligenceService laborIntelligenceService;
    private final LocationHierarchyService locationHierarchyService;

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + WorkorderPermissions.LABOR_INTELLIGENCE_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", WorkorderPermissions.LABOR_INTELLIGENCE_VIEW})
    @GetMapping(value = "/operations", produces = MediaType.APPLICATION_JSON_VALUE)
    @EmitEvent(id = "WORKORDER_LABOR_INTELLIGENCE_LIST", apiVersion = "1")
    @Operation(
            operationId = "listLaborIntelligence",
            summary = "List What Finished Work Says About Each Operation",
            description = """
            Aggregates finished, clocked service lines per operation and shop into the shop's median and mean \
            actual time, the median guide baseline those lines were quoted against, the variance between them, \
            and — once the sample is deep enough — a suggested standard, widest variance first.
            Use this tool to find where a shop consistently beats or misses book time and to source a candidate \
            Durion labor standard; do not use it to price or schedule a specific job, which is resolveLaborTime, \
            and note that nothing here is applied automatically — promoting a suggestion means authoring it as \
            a labor standard in pos-catalog, deliberately.
            Preconditions: only lines carrying a guide baseline count, since an actual with nothing to compare \
            it against would move the variance without changing any real estimate; shops are reported \
            separately, never pooled; a technician's median counts only lines that technician worked alone, \
            because a split line says nothing about either one's speed; and results are not grouped by vehicle \
            class, since pos-workorder holds VIN, plate and odometer but no make or model.
            Required inputs: none — operationCode, locationId and minSamples are all optional, and minSamples \
            may raise the suggestion threshold but never lower it below the configured floor. A caller whose \
            workorder:labor_intelligence:view grant is location-scoped must have a supplied locationId within \
            reach, and without one sees only the shops within reach (ADR-0061).
            Emits a WORKORDER_LABOR_INTELLIGENCE_LIST event; no state changes.
            Returns 200 with one row per operation and shop, an empty list when no finished line yet \
            carries both a baseline and clocked time, and 403 LOCATION_SCOPE_DENIED when the caller's \
            location scope does not cover the supplied locationId.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "One row per operation and shop, widest variance first.",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = LaborIntelligenceRow.class))))
    @ApiResponse(
            responseCode = "401",
            description = "Authentication required.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Insufficient permissions (ApiError.code FORBIDDEN), or " + LOCATION_SCOPE_DENIED_DESCRIPTION,
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<LaborIntelligenceRow>> listLaborIntelligence(
            @Parameter(description = "Narrow to one Durion operation code", example = "TIRE-ROTATION")
                    @RequestParam(required = false)
                    String operationCode,
            @Parameter(description = "Narrow to one shop; omitted reports every shop separately")
                    @RequestParam(required = false)
                    UUID locationId,
            @Parameter(description = "Raise the suggestion threshold above the configured floor", example = "10")
                    @RequestParam(required = false)
                    @Min(value = 1, message = "minSamples must be at least 1")
                    Integer minSamples) {
        // ADR-0061 §3 (#1872): locationId is an optional filter, so this is the narrow shape. A
        // supplied shop is gated; an absent one restricts a scoped caller to their reach and leaves
        // an unscoped caller (including ROLE_ADMIN without the permission) unrestricted.
        LocationScope scope = SecurityContextHelper.locationScope();
        Set<UUID> locationIds;
        if (locationId != null) {
            scope.require(WorkorderPermissions.LABOR_INTELLIGENCE_VIEW, locationId);
            locationIds = Set.of(locationId);
        } else {
            Optional<Reach> reach = scope.reach(WorkorderPermissions.LABOR_INTELLIGENCE_VIEW);
            if (reach.isEmpty()) {
                locationIds = null;
            } else {
                // An empty reach is an empty report, never an unrestricted one.
                locationIds = locationHierarchyService.reachableLocations(reach.get());
                if (locationIds.isEmpty()) {
                    return ResponseEntity.ok(List.of());
                }
            }
        }
        return ResponseEntity.ok(laborIntelligenceService.operations(operationCode, locationIds, minSamples));
    }
}
