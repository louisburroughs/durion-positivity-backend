package com.positivity.inventory.internal.controller;

import com.positivity.inventory.internal.dto.traceability.LotTraceabilityResponse;
import com.positivity.inventory.internal.dto.traceability.SerialTraceabilityResponse;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.inventory.internal.tracking.service.InventoryTraceabilityService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lot/serial traceability read endpoints (odoo-parity E5, issue #1051).
 *
 * <p>Reconstructs the upstream origin (PO → ASN → goods receipt) and downstream movement chain
 * (putaway → pick → consumption → return → scrap → transfer) of a lot or serial unit from the
 * append-only ledger and the lot/serial linkages. Both endpoints are pure reads guarded by
 * {@code inventory:ledger:view} (the ledger read authority named by the story); following the
 * ledger/serial read precedent they carry no {@code @EmitEvent}.
 */
@RestController
@RequestMapping("/v1/inventory")
@RequiredArgsConstructor
@Tag(name = "Traceability", description = "Upstream/downstream traceability for lots and serial units")
public class InventoryTraceabilityController {

    private final InventoryTraceabilityService traceabilityService;

    /**
     * Assembles the traceability tree for one lot.
     *
     * @param lotId the lot id
     * @return the lot's upstream origin and downstream movement chain
     */
    @GetMapping("/lots/{lotId}/traceability")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:ledger:view"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.LEDGER_VIEW + "')")
    @Operation(
            operationId = "traceInventoryLot",
            summary = "Lot traceability",
            description = """
                    Returns a lot's upstream origin (purchase order, ASN, goods receipt, vendor and receipt date) \
                    and its downstream movement chain (putaway, pick, consumption, return, scrap, transfer), \
                    reconstructed chronologically from the lot-tagged append-only ledger entries.
                    Use this tool for recall or quality investigations on a lot; use getInventoryLot instead for \
                    current per-location on-hand, and use traceSerialUnit for a single serialized unit's chain.
                    Preconditions: the lot must exist.
                    Required inputs: lotId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only reconstruction from the ledger.
                    Returns 404 when no lot exists for the supplied id.
                    """,
            tags = {"Traceability"})
    @ApiResponse(responseCode = "200", description = "Lot traceability assembled")
    @ApiResponse(
            responseCode = "403",
            description = "Missing inventory:ledger:view",
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
    public ResponseEntity<LotTraceabilityResponse> getLotTraceability(
            @Parameter(description = "Lot ID", required = true) @PathVariable UUID lotId) {
        return ResponseEntity.ok(traceabilityService.getLotTraceability(lotId));
    }

    /**
     * Assembles the traceability tree for one serial unit.
     *
     * @param serialUnitId the serial unit id
     * @return the serial's upstream origin, downstream chain, and warranty holds
     */
    @GetMapping("/serial-units/{serialUnitId}/traceability")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:ledger:view"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.LEDGER_VIEW + "')")
    @Operation(
            operationId = "traceSerialUnit",
            summary = "Serial traceability",
            description = """
                    Returns a serial unit's upstream origin (purchase order, ASN and goods receipt via its lot) and \
                    its downstream chain (receipt, issue, workorder, return, scrap) from the serial's receipt and \
                    consumption ledger linkages, plus any warranty-hold linkage joined by serial number.
                    Use this tool to investigate one serialized unit's history; use traceInventoryLot instead for a \
                    whole lot's chain, and use getSerialUnit for just the current status and location.
                    Preconditions: the serial unit must exist.
                    Required inputs: serialUnitId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only reconstruction from the ledger.
                    Returns 404 when no serial unit exists for the supplied id.
                    """,
            tags = {"Traceability"})
    @ApiResponse(responseCode = "200", description = "Serial traceability assembled")
    @ApiResponse(
            responseCode = "403",
            description = "Missing inventory:ledger:view",
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
    public ResponseEntity<SerialTraceabilityResponse> getSerialTraceability(
            @Parameter(description = "Serial unit ID", required = true) @PathVariable UUID serialUnitId) {
        return ResponseEntity.ok(traceabilityService.getSerialTraceability(serialUnitId));
    }
}
