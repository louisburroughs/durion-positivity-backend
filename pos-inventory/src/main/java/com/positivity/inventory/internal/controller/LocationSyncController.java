package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.sync.LocationSyncRunResponse;
import com.positivity.inventory.internal.dto.sync.SyncLogResponse;
import com.positivity.inventory.internal.enums.LocationSyncOutcome;
import com.positivity.inventory.service.LocationSyncService;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Location roster sync endpoints (CAP-214 #40): on-demand sync trigger and
 * the sync audit trail consumed by the location-sync UI.
 */
@RestController
@RequestMapping("/v1/inventory")
@RequiredArgsConstructor
@Validated
@Tag(name = "Location Sync", description = "Location roster sync trigger and audit log endpoints")
public class LocationSyncController {

    private final LocationSyncService locationSyncService;

    @PostMapping("/locations/sync")
    @EmitEvent(id = "INVENTORY_LOCATION_SYNC_TRIGGER", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:location:sync"})
    @PreAuthorize("hasAuthority('inventory:location:sync')")
    @Operation(
            operationId = "triggerLocationSync",
            summary = "Trigger a location roster re-sync",
            description = "Requests an administrative re-emit of pos-location's outbox on location.commands.v1 "
                    + "(ADR-0044 #892); the location.events.v1 consumer then idempotently repairs the local "
                    + "reference table, so the run completes asynchronously with outcome REQUESTED. Re-sending "
                    + "the same Idempotency-Key returns the original run instead of requesting again.",
            tags = {"Location Sync"})
    @ApiResponse(
            responseCode = "202",
            description = "Re-emit requested (outcome REQUESTED) or the failure recorded in the sync log",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LocationSyncRunResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required sync authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<LocationSyncRunResponse> triggerLocationSync(
            @Parameter(description = "Client-generated key that makes retries idempotent")
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        String triggeredBy = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        LocationSyncRunResponse response = locationSyncService.triggerSync(triggeredBy, idempotencyKey, correlationId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/sync-logs")
    @EmitEvent(id = "INVENTORY_LOCATION_SYNC_LOG_LIST", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:location:view"})
    @PreAuthorize("hasAuthority('inventory:location:view')")
    @Operation(
            operationId = "listSyncLogs",
            summary = "List location sync logs",
            description = "Lists one page of location sync audit entries, newest first, optionally filtered by "
                    + "outcome. Accepts page/size or the pageIndex/pageSize aliases; response is a plain array.",
            tags = {"Location Sync"})
    @ApiResponse(
            responseCode = "200",
            description = "Sync log entries returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = SyncLogResponse.class))))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required location view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<SyncLogResponse>> listSyncLogs(
            @Parameter(description = "Filter by outcome") @RequestParam(required = false) LocationSyncOutcome outcome,
            @Parameter(description = "Page index (0-based)") @PositiveOrZero @RequestParam(required = false)
                    Integer page,
            @Parameter(description = "Page size") @Positive @RequestParam(required = false) Integer size,
            @Parameter(description = "Alias of page used by SDK clients")
                    @PositiveOrZero
                    @RequestParam(required = false)
                    Integer pageIndex,
            @Parameter(description = "Alias of size used by SDK clients") @Positive @RequestParam(required = false)
                    Integer pageSize) {
        int effectivePage = page != null ? page : (pageIndex != null ? pageIndex : 0);
        int effectiveSize = size != null ? size : (pageSize != null ? pageSize : 50);
        return ResponseEntity.ok(locationSyncService.listSyncLogs(outcome, effectivePage, effectiveSize));
    }

    @GetMapping("/sync-logs/{syncLogId}")
    @EmitEvent(id = "INVENTORY_LOCATION_SYNC_LOG_GET", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:location:view"})
    @PreAuthorize("hasAuthority('inventory:location:view')")
    @Operation(
            operationId = "getSyncLog",
            summary = "Get a location sync log entry",
            description = "Returns one location sync audit entry by identifier.",
            tags = {"Location Sync"})
    @ApiResponse(
            responseCode = "200",
            description = "Sync log entry returned",
            content =
                    @Content(mediaType = "application/json", schema = @Schema(implementation = SyncLogResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Sync log entry not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<SyncLogResponse> getSyncLog(
            @Parameter(description = "Sync log identifier", required = true) @PathVariable UUID syncLogId) {
        return ResponseEntity.ok(locationSyncService.getSyncLog(syncLogId));
    }
}
