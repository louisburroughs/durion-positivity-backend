package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.reallocation.ReallocateRequest;
import com.positivity.inventory.internal.dto.reallocation.ReallocateResponse;
import com.positivity.inventory.service.AllocationReallocationService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
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
@RequestMapping("/v1/inventory/allocations")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('inventory:allocations:reallocate')")
@Tag(name = "Reallocation", description = "Allocation rebalancing endpoints")
public class ReallocationController {

    private final AllocationReallocationService allocationReallocationService;

    @PostMapping("/reallocate")
    @EmitEvent(id = "INVENTORY_ALLOCATION_REALLOCATE", apiVersion = "1")
    @Operation(
            summary = "Reallocate inventory allocations",
            description = "Rebalances existing allocations for a stock item based on priority and available inventory")
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
    public ResponseEntity<ReallocateResponse> reallocate(@Valid @RequestBody ReallocateRequest request) {
        return ResponseEntity.ok(allocationReallocationService.reallocate(request));
    }
}
