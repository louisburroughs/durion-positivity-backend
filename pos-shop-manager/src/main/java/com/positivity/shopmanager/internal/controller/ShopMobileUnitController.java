package com.positivity.shopmanager.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shopmanager.internal.service.MobileUnitServiceImpl;
import com.positivity.shopmanager.service.MobileUnitService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "Shop Mobile Unit API", description = "Operations for managing mobile units within shop locations")
@RestController
@RequestMapping("/v1/shop-manager")
@RequiredArgsConstructor
public class ShopMobileUnitController {

    private final MobileUnitService mobileUnitService;

    @Operation(summary = "Get mobile units", description = "List all mobile units or get a specific mobile unit detail by locationId and bayId.")
    @ApiResponse(responseCode = "200", description = "Mobile units retrieved successfully.")
    @GetMapping({ "/mobileUnit", "/{locationId}/mobileUnit/{bayId}" })
    @PreAuthorize("hasAuthority('shop:bay:view')")
    public ResponseEntity<Object> getMobileUnits(
            @Parameter(description = "Location ID (optional for specific mobile unit)") @PathVariable(required = false) Long locationId,
            @Parameter(description = "Bay ID (optional for specific mobile unit)") @PathVariable(required = false) Long bayId) {
        log.info("Fetching mobile units - locationId={}, bayId={}", locationId, bayId);
        Object mobileUnits = mobileUnitService.getMobileUnits(locationId, bayId);
        return ResponseEntity.ok(mobileUnits);
    }

    @Operation(summary = "Create mobile unit", description = "Create a new mobile unit for a specific shop location. Validate baseLocationId and capabilities.")
    @ApiResponse(responseCode = "200", description = "Mobile unit created successfully.")
    @EmitEvent(id = "SHOP_MOBILE_UNIT_CREATE", apiVersion = "1")
    @PostMapping("/{locationId}/mobileUnit")
    @PreAuthorize("hasAuthority('shop:bay:create')")
    public ResponseEntity<Object> createMobileUnit(
            @Parameter(description = "Shop location ID", example = "1") @PathVariable Long locationId,
            @Parameter(description = "Mobile unit creation request body") @RequestBody(required = false) Object request) {
        log.info("Creating mobile unit for shop location ID: {}", locationId);
        Object mobileUnit = mobileUnitService.createMobileUnit(locationId, request);
        return ResponseEntity.ok(mobileUnit);
    }

    @Operation(summary = "Manage mobile units", description = "Create or update mobile units in bulk.")
    @ApiResponse(responseCode = "200", description = "Mobile units managed successfully.")
    @EmitEvent(id = "SHOP_MOBILE_UNIT_MANAGE", apiVersion = "1")
    @PutMapping("/mobileUnit")
    @PreAuthorize("hasAuthority('shop:bay:edit')")
    public ResponseEntity<Object> manageMobileUnits(
            @Parameter(description = "Mobile unit management request body") @RequestBody(required = false) Object request) {
        log.info("Managing mobile units");
        Object result = mobileUnitService.manageMobileUnits(request);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Delete mobile unit", description = "Delete a specific mobile unit by locationId and bayId.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Mobile unit deleted successfully."),
            @ApiResponse(responseCode = "404", description = "Mobile unit not found.")
    })
    @DeleteMapping("/{locationId}/mobileUnit/{bayId}")
    @PreAuthorize("hasAuthority('shop:bay:edit')")
    public ResponseEntity<Void> deleteMobileUnit(
            @Parameter(description = "Shop location ID", example = "1") @PathVariable Long locationId,
            @Parameter(description = "Bay ID", example = "1") @PathVariable Long bayId) {
        log.info("Deleting mobile unit ID: {} for shop location ID: {}", bayId, locationId);
        mobileUnitService.deleteMobileUnit(locationId, bayId);
        return ResponseEntity.noContent().build();
    }
}
