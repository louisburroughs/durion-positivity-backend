package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.cyclecount.service.CycleCountToleranceService;
import com.positivity.inventory.internal.dto.cyclecount.tolerance.CreateCycleCountToleranceRequest;
import com.positivity.inventory.internal.dto.cyclecount.tolerance.CycleCountToleranceResponse;
import com.positivity.inventory.internal.dto.cyclecount.tolerance.UpdateCycleCountToleranceRequest;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin CRUD for cycle-count tolerance configuration (ADR-0055 stage 4, #1416; issue #1414 owner
 * spec).
 */
@RestController
@RequestMapping("/v1/inventory/cycleCountTolerances")
@RequiredArgsConstructor
@Slf4j
@Tag(
        name = "Cycle Count Tolerances",
        description = "Configure product/location tolerance bounds for cycle-count reconciliation")
public class CycleCountToleranceController {

    private final CycleCountToleranceService toleranceService;

    @PostMapping
    @EmitEvent(id = "INVENTORY_CYCLE_COUNT_TOLERANCE_CREATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:cycle_count_tolerance:manage"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.CYCLE_COUNT_TOLERANCE_MANAGE + "')")
    @Operation(
            operationId = "createCycleCountTolerance",
            summary = "Create a cycle-count tolerance configuration",
            description = """
                    Creates a tolerance configuration scoped by product, storage location, both, or neither \
                    (the global default), as an absolute quantity bound, a percentage bound, or both.
                    Use this tool to configure how much variance a bulk product or location may show before a \
                    cycle count is flagged for investigation; do not use this for approval thresholds, which are \
                    a separate configuration governing who must sign off on an adjustment once one is created.
                    Preconditions: no other active tolerance may already occupy the exact same scope.
                    Required inputs: createdBy; productId, storageLocation, absoluteTolerance and \
                    percentageTolerance are all optional.
                    Emits an INVENTORY_CYCLE_COUNT_TOLERANCE_CREATE event.
                    Returns 400 when an active tolerance already exists for this scope.
                    """,
            tags = {"Cycle Count Tolerances"})
    @ApiResponse(
            responseCode = "201",
            description = "Tolerance created",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CycleCountToleranceResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request or scope already configured")
    public ResponseEntity<CycleCountToleranceResponse> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Tolerance scope (product, storage location, or both) and its absolute"
                                    + " and/or percentage bounds.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                            name = "Product+location tolerance",
                                                            value = """
                                                                    {"productId":"01960003-0000-7000-8000-000000000001",
                                                                     "storageLocation":"TANK-04",
                                                                     "absoluteTolerance":50,
                                                                     "percentageTolerance":1.5,
                                                                     "createdBy":"user-10042"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreateCycleCountToleranceRequest request) {
        log.info("Creating cycle count tolerance for productId={}", request.getProductId());
        return ResponseEntity.status(HttpStatus.CREATED).body(toleranceService.create(request));
    }

    @PutMapping("/{toleranceId}")
    @EmitEvent(id = "INVENTORY_CYCLE_COUNT_TOLERANCE_UPDATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:cycle_count_tolerance:manage"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.CYCLE_COUNT_TOLERANCE_MANAGE + "')")
    @Operation(
            operationId = "updateCycleCountTolerance",
            summary = "Replace a cycle-count tolerance's bounds and active state",
            description = """
                    Replaces a tolerance configuration's absolute bound, percentage bound, and active flag; the \
                    scope (productId, storageLocation) is immutable — recreate the row to change scope.
                    Use this tool to widen, tighten, or deactivate an existing tolerance; do not use this to \
                    change scope — create a new tolerance for the new scope instead.
                    Preconditions: the tolerance must exist; reactivating (active=false -> true) requires no other \
                    active row already occupy the same scope.
                    Required inputs: toleranceId (UUID) path parameter, active (boolean); absoluteTolerance and \
                    percentageTolerance are set verbatim from the request, null included (PUT semantics).
                    Emits an INVENTORY_CYCLE_COUNT_TOLERANCE_UPDATE event.
                    Returns 404 when no tolerance exists for the id, 400 when reactivation would collide with an \
                    already-active row at the same scope.
                    """,
            tags = {"Cycle Count Tolerances"})
    @ApiResponse(
            responseCode = "200",
            description = "Tolerance updated",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CycleCountToleranceResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Tolerance not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "400", description = "Reactivation would collide with an existing active scope")
    public ResponseEntity<CycleCountToleranceResponse> update(
            @Parameter(description = "Tolerance ID", required = true) @PathVariable UUID toleranceId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Replacement absolute/percentage bounds and active flag, applied"
                                    + " verbatim (PUT semantics).",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                            name = "Widen the absolute bound",
                                                            value = """
                                                                    {"absoluteTolerance":75,
                                                                     "percentageTolerance":2.0,
                                                                     "active":true}
                                                                    """)))
                    @Valid
                    @RequestBody
                    UpdateCycleCountToleranceRequest request) {
        log.info("Updating cycle count tolerance {}", toleranceId);
        return ResponseEntity.ok(toleranceService.update(toleranceId, request));
    }

    @GetMapping("/{toleranceId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:cycle_count:view"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.CYCLE_COUNT_VIEW + "')")
    @Operation(
            operationId = "getCycleCountTolerance",
            summary = "Get a cycle-count tolerance configuration",
            description = """
                    Returns one tolerance configuration by id.
                    Use this tool when the toleranceId is already known; use listCycleCountTolerances instead to \
                    browse all configured tolerances.
                    Preconditions: the tolerance must exist.
                    Required inputs: toleranceId (UUID) path parameter; there is no request body.
                    No events are emitted; this is a read-only projection.
                    Returns 404 when no tolerance exists for the supplied id.
                    """,
            tags = {"Cycle Count Tolerances"})
    @ApiResponse(
            responseCode = "200",
            description = "Tolerance found",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CycleCountToleranceResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Tolerance not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<CycleCountToleranceResponse> get(
            @Parameter(description = "Tolerance ID", required = true) @PathVariable UUID toleranceId) {
        return ResponseEntity.ok(toleranceService.get(toleranceId));
    }

    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:cycle_count:view"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.CYCLE_COUNT_VIEW + "')")
    @Operation(
            operationId = "listCycleCountTolerances",
            summary = "List all cycle-count tolerance configurations",
            description = """
                    Returns every tolerance configuration, active and inactive, newest first.
                    Use this tool to browse or audit configured tolerances; use getCycleCountTolerance instead \
                    when the toleranceId is already known.
                    Preconditions: none.
                    Required inputs: none; there is no paging or filtering.
                    No events are emitted; this is a read-only projection.
                    Returns 200 with an empty array when none are configured.
                    """,
            tags = {"Cycle Count Tolerances"})
    @ApiResponse(responseCode = "200", description = "Tolerances retrieved")
    public ResponseEntity<List<CycleCountToleranceResponse>> list() {
        return ResponseEntity.ok(toleranceService.list());
    }

    @DeleteMapping("/{toleranceId}")
    @EmitEvent(id = "INVENTORY_CYCLE_COUNT_TOLERANCE_DELETE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:cycle_count_tolerance:manage"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.CYCLE_COUNT_TOLERANCE_MANAGE + "')")
    @Operation(
            operationId = "deleteCycleCountTolerance",
            summary = "Delete a cycle-count tolerance configuration",
            description = """
                    Permanently removes a tolerance configuration.
                    Use this tool to retire a tolerance no longer wanted; do not use updateCycleCountTolerance with \
                    active=false instead unless the row should be kept around for its audit trail — delete removes \
                    it outright.
                    Preconditions: the tolerance must exist.
                    Required inputs: toleranceId (UUID) path parameter; there is no request body.
                    Emits an INVENTORY_CYCLE_COUNT_TOLERANCE_DELETE event.
                    Returns 404 when no tolerance exists for the supplied id.
                    """,
            tags = {"Cycle Count Tolerances"})
    @ApiResponse(responseCode = "204", description = "Tolerance deleted")
    @ApiResponse(
            responseCode = "404",
            description = "Tolerance not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Void> delete(
            @Parameter(description = "Tolerance ID", required = true) @PathVariable UUID toleranceId) {
        log.info("Deleting cycle count tolerance {}", toleranceId);
        toleranceService.delete(toleranceId);
        return ResponseEntity.noContent().build();
    }
}
