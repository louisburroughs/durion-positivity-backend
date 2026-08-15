package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.reservation.CreateReservationRequest;
import com.positivity.inventory.internal.dto.reservation.PromoteAllocationRequest;
import com.positivity.inventory.internal.dto.reservation.ReservationResponse;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.inventory.service.ReservationService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"inventory:adjustment:create"})
@RequestMapping("/v1/inventory/reservations")
@RequiredArgsConstructor
@Tag(
        name = "Inventory Reservations",
        description = "Reserve, promote, and cancel inventory allocations for workorder lines")
public class ReservationController {

    private final ReservationService reservationService;

    @PostMapping()
    @EmitEvent(id = "INVENTORY_RESERVATION_CREATE_OR_UPDATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.ADJUSTMENT_CREATE + "')")
    @Operation(
            operationId = "createOrUpdateReservation",
            summary = "Create or update a reservation",
            description = """
                    Creates a soft reservation for a workorder line, or updates the existing reservation for that \
                    line to the requested stock item and quantity; creation records one SOFT allocation for the \
                    full quantity with no location and no ledger entry.
                    Use this tool to register parts demand before picking; do not use \
                    promoteReservationAllocation, which hardens an existing allocation to a storage location, and \
                    do not use consumePickedItems, which posts the actual stock decrement.
                    Preconditions: none for creation — no ATP check is performed at this stage, so the reservation \
                    is accepted even when stock is short; an update may not change stockItemId while the \
                    reservation still has located HARD allocations.
                    Required inputs: workorderLineId (UUID), stockItemId (UUID) and requiredQuantity (positive \
                    integer); workorderLineId is the upsert key.
                    Emits an INVENTORY_RESERVATION_CREATE_OR_UPDATE event; no inventory ledger entry is written \
                    for a soft allocation.
                    Returns 201 in both the create and the update case, 409 when stockItemId is changed on a \
                    reservation with located HARD allocations, and 400 when a required field is missing or \
                    requiredQuantity is not positive.
                    """,
            tags = {"Inventory Reservations"})
    @ApiResponse(
            responseCode = "201",
            description = "Reservation created or updated",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ReservationResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required reservation authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Business rule validation failed",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ReservationResponse> createOrUpdateReservation(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "Demand to reserve: the workorder line, the stock item and the quantity;"
                                    + " an existing reservation for the line is updated in place.",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = CreateReservationRequest.class),
                                            examples = @ExampleObject(name = "Reserve four units", value = """
                                                                    {"workorderLineId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01",
                                                                     "stockItemId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02",
                                                                     "requiredQuantity":4}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreateReservationRequest request) {
        ReservationResponse response = reservationService.createOrUpdateReservation(request);
        return ResponseEntity.created(URI.create("/v1/inventory/reservations/" + response.getReservationId()))
                .body(response);
    }

    @PostMapping("/{allocationId}/promote")
    @EmitEvent(id = "INVENTORY_ALLOCATION_PROMOTE_HARD", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.ADJUSTMENT_CREATE + "')")
    @Operation(
            operationId = "promoteReservationAllocation",
            summary = "Promote allocation to hard",
            description = """
                    Promotes an existing allocation to HARD, pinning it to a storage location and recording an \
                    ALLOCATION_CREATED inventory ledger entry against that location.
                    Use this tool when stock is being committed to a location and ATP must be verified; do not use \
                    createOrUpdateReservation, which only registers soft demand, and do not use \
                    consumePickedItems, which decrements stock after picking.
                    Preconditions: the allocation must exist, the storage location must exist and be active, and \
                    available ATP — the stock item's ledger net on-hand minus the reservation's other HARD \
                    quantity — must cover the allocation's quantity.
                    Required inputs: allocationId (UUID) path parameter and storageLocationId (UUID) in the body; \
                    hardenedReason is optional free text.
                    Emits an INVENTORY_ALLOCATION_PROMOTE_HARD event; the reservation becomes FULFILLED or \
                    PARTIALLY_FULFILLED by total allocated quantity, a failed ATP check flips it to BACKORDERED \
                    before the 422 is returned, and a repeat promote of an already-HARD located allocation keeps \
                    its location and writes no duplicate ledger entry.
                    Returns 404 when the allocation or storage location cannot be resolved, 422 with \
                    INSUFFICIENT_ATP when ATP does not cover the quantity, 409 when a different storageLocationId \
                    is supplied for an already-located HARD allocation (relocation via promote is unsupported), \
                    and 400 when storageLocationId is missing or the storage location is inactive.
                    """,
            tags = {"Inventory Reservations"})
    @ApiResponse(
            responseCode = "200",
            description = "Allocation promoted",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ReservationResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required reservation authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Allocation, reservation, or storage location not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Insufficient ATP or business rule validation failed",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ReservationResponse> promoteToHard(
            @Parameter(description = "Allocation identifier to promote", required = true) @PathVariable
                    UUID allocationId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            required = true,
                            description = "Storage location the hardened allocation pins stock to, with an optional"
                                    + " hardening reason.",
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = PromoteAllocationRequest.class),
                                            examples = @ExampleObject(name = "Pin to a storage location", value = """
                                                                    {"storageLocationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a03",
                                                                     "hardenedReason":"Reserved for scheduled workorder pick"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    PromoteAllocationRequest request) {
        return ResponseEntity.ok(reservationService.promoteToHard(allocationId, request));
    }

    @DeleteMapping("/{workorderLineId}")
    @EmitEvent(id = "INVENTORY_RESERVATION_CANCEL", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.ADJUSTMENT_CREATE + "')")
    @Operation(
            operationId = "cancelReservation",
            summary = "Cancel reservation by workorder line",
            description = """
                    Cancels the reservation for a workorder line and releases every allocation under it.
                    Use this tool when the demand is withdrawn; do not use resolveShortage with CANCEL_LINE, which \
                    wraps this cancel in an idempotent shortage-resolution record, and note the path parameter is \
                    the workorderLineId, not the reservationId.
                    Preconditions: a reservation must exist for the workorder line.
                    Required inputs: workorderLineId (UUID) path parameter; there is no request body.
                    Emits an INVENTORY_RESERVATION_CANCEL event; every allocation is marked RELEASED, located HARD \
                    allocations get ALLOCATION_RELEASED ledger entries for their un-released remainder only \
                    (preserving the per-allocation CREATED minus RELEASED invariant), and the reservation ends \
                    CANCELLED with allocatedQuantity 0.
                    Returns 404 when no reservation exists for the workorder line.
                    """,
            tags = {"Inventory Reservations"})
    @ApiResponse(responseCode = "204", description = "Reservation cancelled")
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required reservation authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Reservation not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Void> cancelReservation(
            @Parameter(description = "Workorder line identifier", required = true) @PathVariable UUID workorderLineId) {
        reservationService.cancelReservation(workorderLineId);
        return ResponseEntity.noContent().build();
    }
}
