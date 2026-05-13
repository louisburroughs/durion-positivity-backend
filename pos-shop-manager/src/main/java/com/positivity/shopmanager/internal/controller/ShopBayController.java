package com.positivity.shopmanager.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shopmanager.service.BayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "Shop Bay API", description = "Operations for managing bays within shop locations")
@RestController
@RequestMapping("/v1/shop-manager")
@RequiredArgsConstructor
public class ShopBayController {

    private final BayService bayService;

    @Operation(
            summary = "Get bays",
            description = "List all bays or get a specific bay detail by locationId and bayId.")
    @ApiResponse(responseCode = "200", description = "Bays retrieved successfully.")
    @GetMapping({"/bays", "/{locationId}/bays/{bayId}"})
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"shop:bay:view"})
    @PreAuthorize("hasAuthority('shop:bay:view')")
    public ResponseEntity<Object> getBays(
            @Parameter(description = "Location ID (optional for specific bay)") @PathVariable(required = false)
                    Long locationId,
            @Parameter(description = "Bay ID (optional for specific bay)") @PathVariable(required = false) Long bayId) {
        log.info("Fetching bays - locationId(mask)={}, bayId(mask)={}", maskForLog(locationId), maskForLog(bayId));
        Object bays = bayService.getBays(locationId, bayId);
        return ResponseEntity.ok(bays);
    }

    @Operation(summary = "Create bay", description = "Create a new bay for a specific shop location.")
    @ApiResponse(responseCode = "200", description = "Bay created successfully.")
    @EmitEvent(id = "SHOP_BAY_CREATE", apiVersion = "1")
    @PostMapping("/{locationId}/bays")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"shop:bay:create"})
    @PreAuthorize("hasAuthority('shop:bay:create')")
    public ResponseEntity<Object> createBay(
            @Parameter(description = "Shop location ID", example = "1") @PathVariable Long locationId,
            @Parameter(description = "Bay creation request body") @RequestBody(required = false) Object request) {
        log.info("Creating bay for shop location ID(mask): {}", maskForLog(locationId));
        Object bay = bayService.createBay(locationId, request);
        return ResponseEntity.ok(bay);
    }

    @Operation(summary = "Manage bays", description = "Create or update bays in bulk.")
    @ApiResponse(responseCode = "200", description = "Bays managed successfully.")
    @EmitEvent(id = "SHOP_BAY_MANAGE", apiVersion = "1")
    @PutMapping("/bays")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"shop:bay:edit"})
    @PreAuthorize("hasAuthority('shop:bay:edit')")
    public ResponseEntity<Object> manageBays(
            @Parameter(description = "Bay management request body") @RequestBody(required = false) Object request) {
        log.info("Managing bays");
        Object result = bayService.manageBays(request);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Delete bay", description = "Delete a specific bay by locationId and bayId.")
    @ApiResponse(responseCode = "204", description = "Bay deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Bay not found.")
    @DeleteMapping("/{locationId}/bays/{bayId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"shop:bay:edit"})
    @PreAuthorize("hasAuthority('shop:bay:edit')")
    @EmitEvent(id = "SHOP_BAY_DELETE", apiVersion = "1")
    public ResponseEntity<Void> deleteBay(
            @Parameter(description = "Shop location ID", example = "1") @PathVariable Long locationId,
            @Parameter(description = "Bay ID", example = "1") @PathVariable Long bayId) {
        log.info("Deleting bay ID(mask): {} for shop location ID(mask): {}", maskForLog(bayId), maskForLog(locationId));
        bayService.deleteBay(locationId, bayId);
        return ResponseEntity.noContent().build();
    }

    private String maskForLog(Object value) {
        if (value == null) {
            return "null";
        }
        String sanitized =
                value.toString().replace('\r', '_').replace('\n', '_').replace('\t', '_');
        int length = sanitized.length();
        if (length <= 4) {
            return "****";
        }
        return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
    }
}
