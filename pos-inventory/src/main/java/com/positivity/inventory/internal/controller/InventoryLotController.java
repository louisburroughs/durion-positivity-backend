package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.lot.LotDetailResponse;
import com.positivity.inventory.internal.dto.lot.LotResponse;
import com.positivity.inventory.internal.enums.InventoryLotStatus;
import com.positivity.inventory.service.InventoryLotService;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only REST endpoints over the lot master (odoo-parity E1, issue #1038).
 *
 * <p>Lots are created by the inbound receipt paths, never through this API. Per-lot on-hand
 * is quantity data, so both endpoints are guarded by {@code inventory:on_hand:view}
 * (DECISION-INVENTORY-011: quantities stay sensitive-by-default).
 */
@RestController
@RequestMapping("/v1/inventory/lots")
@RequiredArgsConstructor
@Tag(name = "Lots", description = "Lot master records captured on inbound receipts, with per-lot on-hand")
public class InventoryLotController {

    private final InventoryLotService lotService;

    /**
     * Lists lot master records matching the optional filters, newest received first.
     *
     * @param stockItemId optional stock item (catalog product id) filter
     * @param status optional lifecycle status filter
     * @param lotNumber optional exact lot-number filter
     * @return matching lots
     */
    @GetMapping
    @EmitEvent(id = "INVENTORY_LOT_LIST", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:on_hand:view"})
    @PreAuthorize("hasAuthority('inventory:on_hand:view')")
    @Operation(
            summary = "List lots",
            description = "Lists lot master records filtered by stock item, status, and lot number,"
                    + " newest received first",
            tags = {"Lots"})
    @ApiResponse(
            responseCode = "200",
            description = "Lots retrieved",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = LotResponse.class))))
    @ApiResponse(
            responseCode = "403",
            description = "Missing inventory:on_hand:view",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<LotResponse>> listLots(
            @Parameter(description = "Filter by stock item (catalog product id)") @RequestParam(required = false)
                    String stockItemId,
            @Parameter(description = "Filter by lifecycle status") @RequestParam(required = false)
                    InventoryLotStatus status,
            @Parameter(description = "Filter by exact lot number") @RequestParam(required = false) String lotNumber) {
        return ResponseEntity.ok(lotService.listLots(stockItemId, status, lotNumber));
    }

    /**
     * Retrieves one lot with its per-location on-hand balances.
     *
     * @param lotId the lot id
     * @return the lot detail
     */
    @GetMapping("/{lotId}")
    @EmitEvent(id = "INVENTORY_LOT_GET", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:on_hand:view"})
    @PreAuthorize("hasAuthority('inventory:on_hand:view')")
    @Operation(
            summary = "Get lot details",
            description = "Retrieves one lot master record with its per-location on-hand from the per-lot"
                    + " stock summary rows",
            tags = {"Lots"})
    @ApiResponse(responseCode = "200", description = "Lot found")
    @ApiResponse(
            responseCode = "403",
            description = "Missing inventory:on_hand:view",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Lot not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<LotDetailResponse> getLot(
            @Parameter(description = "Lot ID", required = true) @PathVariable UUID lotId) {
        return ResponseEntity.ok(lotService.getLot(lotId));
    }
}
