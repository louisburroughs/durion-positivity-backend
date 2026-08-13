package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.reallocation.ReallocateRequest;
import com.positivity.inventory.internal.dto.reallocation.ReallocateResponse;
import com.positivity.inventory.service.AllocationReallocationService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"inventory:allocations:reallocate"})
@RequestMapping("/v1/inventory/allocations")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('inventory:allocations:reallocate')")
@Tag(name = "Reallocation", description = "Allocation rebalancing endpoints")
public class ReallocationController {

    private final AllocationReallocationService allocationReallocationService;

    @PostMapping("/reallocate")
    @EmitEvent(id = "INVENTORY_ALLOCATION_REALLOCATE", apiVersion = "1")
    @Operation(
            operationId = "reallocateAllocations",
            summary = "Reallocate inventory allocations",
            description = """
                    Rebalances the allocated quantities of every reservation of one stock item, redistributing the \
                    reservations' current total allocated pool by effective priority.
                    Use this tool after a supply or priority change to re-decide which reservations hold stock; do \
                    not use promoteReservationAllocation, which hardens a single allocation, and do not use \
                    resolveShortage, which creates resolution artifacts for one short allocation.
                    Preconditions: none beyond reservations existing for the stock item; the pool redistributed is \
                    the current total allocated quantity across those reservations, not ledger on-hand.
                    Required inputs: stockItemId (UUID); triggerType (an AllocationAuditReasonCode name, falling \
                    back to MANUAL_OVERRIDE when absent or unrecognized) and triggerReferenceId are optional audit \
                    fields.
                    Emits an INVENTORY_ALLOCATION_REALLOCATE event and writes one allocation-audit row per \
                    reservation; ordering is effective priority ascending (1 is critical, and priority ages one \
                    step per 24 hours after a 24-hour grace period), then due date, waiting-since, schedule start \
                    and creation time, each reservation receives its full required quantity or zero (no partial \
                    awards), and no ledger entries are written.
                    Returns 400 when stockItemId is missing, and 200 with atpAfterReallocation (ledger on-hand \
                    minus total allocated) otherwise.
                    """,
            tags = {"Reallocation"})
    @ApiResponse(
            responseCode = "200",
            description = "Reallocation completed",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ReallocateResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required reallocation authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Reallocation failed business validation",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ReallocateResponse> reallocate(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The stock item to rebalance, with optional audit fields naming what"
                                    + " triggered the reallocation.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Rebalance after replenishment",
                                                            value = """
                                                                    {"stockItemId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01",
                                                                     "triggerType":"STOCK_REPLENISHED",
                                                                     "triggerReferenceId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    ReallocateRequest request) {
        return ResponseEntity.ok(allocationReallocationService.reallocate(request));
    }
}
