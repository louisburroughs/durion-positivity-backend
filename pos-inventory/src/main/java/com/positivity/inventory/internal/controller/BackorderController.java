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
            summary = "List backorders",
            description = "Lists backorders filtered by status, SKU, location, and workorder line",
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
            summary = "Get backorder details",
            description = "Retrieves one backorder",
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
