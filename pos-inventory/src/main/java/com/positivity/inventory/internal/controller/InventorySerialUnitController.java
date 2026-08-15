package com.positivity.inventory.internal.controller;

import com.positivity.inventory.internal.dto.serial.SerialUnitResponse;
import com.positivity.inventory.internal.enums.InventorySerialStatus;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.inventory.service.InventorySerialUnitService;
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
 * Read endpoints over the serial unit master (odoo-parity E4, issue #1050).
 *
 * <p>Serial units are created and transitioned exclusively by the ledger posting funnel (serial
 * enumeration on receipt, consumption on issue), never through this API. Because they expose
 * per-unit on-hand (quantity data), the read endpoints are guarded by
 * {@code inventory:on_hand:view} (DECISION-INVENTORY-011: quantities stay sensitive-by-default).
 * Pure quantity-read GETs carry no {@code @EmitEvent}, following the ShortageController/
 * BackorderController precedent. The {@code serialNumber} filter is the warranty join point —
 * {@code WarrantyPartReturnHold.serialNumber} resolves to a stock record here.
 */
@RestController
@RequestMapping("/v1/inventory/serial-units")
@RequiredArgsConstructor
@Tag(
        name = "Serial Units",
        description = "Serial unit master records enumerated on inbound receipts for SERIAL-tracked SKUs")
public class InventorySerialUnitController {

    private final InventorySerialUnitService serialUnitService;

    /**
     * Lists serial unit master records matching the optional filters, newest first.
     *
     * @param stockItemId optional stock item (catalog product id) filter
     * @param status optional lifecycle status filter
     * @param serialNumber optional exact serial-number filter (warranty join point)
     * @return matching serial units
     */
    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:on_hand:view"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.INVENTORY_VIEW + "')")
    @Operation(
            operationId = "listSerialUnits",
            summary = "List serial units",
            description = """
                    Lists serial unit master records newest first, optionally filtered by stock item, lifecycle \
                    status and exact serial number — the warranty join point that resolves a part-return hold's \
                    serial number to a stock record.
                    Use this tool to discover a serialUnitId or resolve a serial number; use getSerialUnit instead \
                    when the id is known, and use traceSerialUnit for the movement history.
                    Preconditions: none; serial units are created and transitioned exclusively by the ledger \
                    posting funnel, never through this API.
                    Required inputs: none; stockItemId (catalog product id), status (IN_STOCK, ISSUED, RETURNED, \
                    SCRAPPED) and serialNumber are optional query filters.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty array when nothing matches, so an empty result is not an error \
                    condition.
                    """,
            tags = {"Serial Units"})
    @ApiResponse(
            responseCode = "200",
            description = "Serial units retrieved",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = SerialUnitResponse.class))))
    @ApiResponse(
            responseCode = "403",
            description = "Missing inventory:on_hand:view",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<SerialUnitResponse>> listSerialUnits(
            @Parameter(description = "Filter by stock item (catalog product id)") @RequestParam(required = false)
                    String stockItemId,
            @Parameter(description = "Filter by lifecycle status") @RequestParam(required = false)
                    InventorySerialStatus status,
            @Parameter(description = "Filter by exact serial number") @RequestParam(required = false)
                    String serialNumber) {
        return ResponseEntity.ok(serialUnitService.listSerialUnits(stockItemId, status, serialNumber));
    }

    /**
     * Retrieves one serial unit by id.
     *
     * @param serialUnitId the serial unit id
     * @return the serial unit
     */
    @GetMapping("/{serialUnitId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:on_hand:view"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.INVENTORY_VIEW + "')")
    @Operation(
            operationId = "getSerialUnit",
            summary = "Get serial unit",
            description = """
                    Returns one serial unit master record with its current status, location, lot linkage and \
                    receipt, consumption and workorder linkages.
                    Use this tool when the serialUnitId is already known; use listSerialUnits instead to search by \
                    stock item, status or serial number, and use traceSerialUnit for the full movement chain.
                    Preconditions: the serial unit must exist.
                    Required inputs: serialUnitId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no serial unit exists for the supplied id.
                    """,
            tags = {"Serial Units"})
    @ApiResponse(responseCode = "200", description = "Serial unit found")
    @ApiResponse(
            responseCode = "403",
            description = "Missing inventory:on_hand:view",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Serial unit not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<SerialUnitResponse> getSerialUnit(
            @Parameter(description = "Serial unit ID", required = true) @PathVariable UUID serialUnitId) {
        return ResponseEntity.ok(serialUnitService.getSerialUnit(serialUnitId));
    }
}
