package com.positivity.inventory.internal.controller;

import com.positivity.inventory.internal.dto.backorder.BackorderResponse;
import com.positivity.inventory.internal.enums.BackorderStatus;
import com.positivity.inventory.service.BackorderService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read API for backorders (odoo-parity G1, issue #1046).
 *
 * <p>Backorders are opened by the reservation/pick shortage flows and the G2 shortage resolver
 * (service surface {@code BackorderService}), and auto-resolved by inbound availability. This
 * controller exposes only reads; both are quantity-sensitive and gated on
 * {@code inventory:shortage:view} (reused per the G1 no-new-permission rule) — mirroring
 * {@code ShortageController}, the GETs carry no {@code @EmitEvent}.
 */
@RestController
@RequestMapping("/v1/inventory/backorders")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Backorders", description = "Unfulfilled workorder-line demand held open until availability covers it")
public class BackorderController {

    private final BackorderService backorderService;

    /**
     * Lists backorders matching the optional filters, newest first.
     *
     * @param status optional lifecycle-status filter
     * @param sku optional SKU filter
     * @param locationId optional site filter
     * @param workorderLineId optional workorder-line filter
     * @return matching backorders
     */
    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:shortage:view"})
    @PreAuthorize("hasAuthority('inventory:shortage:view')")
    @Operation(
            operationId = "listBackorders",
            summary = "List backorders",
            description = """
                    Lists backorders — unfulfilled workorder-line demand held open until availability covers it — \
                    newest first.
                    Use this tool to monitor open shortages awaiting stock; use getBackorder instead when the \
                    backorderId is already known, and note backorders are opened by the shortage flows \
                    (resolveShortage with BACKORDER), not by any direct create endpoint.
                    Preconditions: none; an empty result is not an error.
                    Required inputs: none — status (OPEN, RESOLVED or CANCELLED), sku, locationId and \
                    workorderLineId are optional filters combined with AND.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty array when nothing matches.
                    """,
            tags = {"Backorders"})
    @ApiResponse(
            responseCode = "200",
            description = "Backorders retrieved",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = BackorderResponse.class))))
    public ResponseEntity<List<BackorderResponse>> listBackorders(
            @Parameter(description = "Filter by lifecycle status") @RequestParam(required = false)
                    BackorderStatus status,
            @Parameter(description = "Filter by SKU / stock-item identifier") @RequestParam(required = false)
                    String sku,
            @Parameter(description = "Filter by site") @RequestParam(required = false) UUID locationId,
            @Parameter(description = "Filter by workorder line") @RequestParam(required = false) UUID workorderLineId) {
        return ResponseEntity.ok(backorderService.listBackorders(status, sku, locationId, workorderLineId));
    }

    /**
     * Retrieves one backorder.
     *
     * @param backorderId the backorder id
     * @return the backorder
     */
    @GetMapping("/{backorderId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:shortage:view"})
    @PreAuthorize("hasAuthority('inventory:shortage:view')")
    @Operation(
            operationId = "getBackorder",
            summary = "Get backorder details",
            description = """
                    Returns one backorder with its shortage quantity, lifecycle status, resolution source and \
                    timestamps.
                    Use this tool when the backorderId is already known; use listBackorders instead to search by \
                    status, SKU, site or workorder line.
                    Preconditions: the backorder must exist.
                    Required inputs: backorderId (UUID) path parameter; there is no request body.
                    No events are emitted and no state changes; auto-resolution happens asynchronously when \
                    inbound stock raises ATP at the backorder's site — oldest-priority-first, and only when the \
                    full quantityShort fits the remaining ATP budget (whole-backorder resolution, no partials).
                    Returns 404 when no backorder exists for the supplied id.
                    """,
            tags = {"Backorders"})
    @ApiResponse(responseCode = "200", description = "Backorder found")
    @ApiResponse(
            responseCode = "404",
            description = "Backorder not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<BackorderResponse> getBackorder(
            @Parameter(description = "Backorder ID", required = true) @PathVariable UUID backorderId) {
        return ResponseEntity.ok(backorderService.getBackorder(backorderId));
    }
}
