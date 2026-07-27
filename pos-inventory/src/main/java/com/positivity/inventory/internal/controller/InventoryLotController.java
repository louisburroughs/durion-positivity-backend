package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.lot.LotDetailResponse;
import com.positivity.inventory.internal.dto.lot.LotExpirationUpdateRequest;
import com.positivity.inventory.internal.dto.lot.LotResponse;
import com.positivity.inventory.internal.dto.lot.LotStatusUpdateRequest;
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
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints over the lot master (odoo-parity E1, issue #1038; E3, issue #1047).
 *
 * <p>Lots are created by the inbound receipt paths, never through this API. The read endpoints
 * expose per-lot on-hand (quantity data) and are guarded by {@code inventory:on_hand:view}
 * (DECISION-INVENTORY-011: quantities stay sensitive-by-default). The E3 lot-management endpoints
 * (status change, expiration set) mutate lifecycle state and are guarded by the stronger
 * {@code inventory:lot:manage}.
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

    /**
     * Changes a lot's lifecycle status: quarantine, recall, or release back to ACTIVE.
     *
     * @param lotId the lot id
     * @param request the target status and mandatory reason
     * @return the updated lot
     */
    @PostMapping("/{lotId}/status")
    @EmitEvent(id = "INVENTORY_LOT_STATUS_UPDATE", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:lot:manage"})
    @PreAuthorize("hasAuthority('inventory:lot:manage')")
    @Operation(
            summary = "Change lot status",
            description = "Quarantines, recalls, or releases a lot back to ACTIVE. QUARANTINED and RECALLED lots"
                    + " are blocked from lot suggestion and outbound movement. Status flip only — physically"
                    + " moving stock to the site's quarantine bin is a separate operator-driven transfer."
                    + " CONSUMED is rejected (reconciler-owned).",
            tags = {"Lots"})
    @ApiResponse(responseCode = "200", description = "Lot status updated")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid target status (e.g. CONSUMED) or missing reason",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Missing inventory:lot:manage",
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
    public ResponseEntity<LotResponse> updateLotStatus(
            @Parameter(description = "Lot ID", required = true) @PathVariable UUID lotId,
            @Valid @RequestBody LotStatusUpdateRequest request) {
        return ResponseEntity.ok(lotService.updateStatus(lotId, request));
    }

    /**
     * Sets or clears a lot's expiration and alert-window dates.
     *
     * @param lotId the lot id
     * @param request the expiration and alert dates (either may be null to clear)
     * @return the updated lot
     */
    @PutMapping("/{lotId}/expiration")
    @EmitEvent(id = "INVENTORY_LOT_EXPIRATION_SET", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:lot:manage"})
    @PreAuthorize("hasAuthority('inventory:lot:manage')")
    @Operation(
            summary = "Set lot expiration",
            description = "Sets or clears a lot's expiration date and alert-window date. Re-dating resets the"
                    + " emit-once alert bookkeeping so the daily expiry scan can alert the new dates.",
            tags = {"Lots"})
    @ApiResponse(responseCode = "200", description = "Lot expiration updated")
    @ApiResponse(
            responseCode = "403",
            description = "Missing inventory:lot:manage",
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
    public ResponseEntity<LotResponse> updateLotExpiration(
            @Parameter(description = "Lot ID", required = true) @PathVariable UUID lotId,
            @Valid @RequestBody LotExpirationUpdateRequest request) {
        return ResponseEntity.ok(lotService.updateExpiration(lotId, request));
    }
}
