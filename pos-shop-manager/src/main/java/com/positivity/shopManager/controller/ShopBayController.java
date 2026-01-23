package com.positivity.shopManager.controller;

import com.positivity.shopManager.service.BayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "Shop Bay API", description = "Operations for managing bays within shop locations")
@RestController
@RequestMapping("/v1/shop-manager")
@RequiredArgsConstructor
public class ShopBayController {

    private final BayService bayService;

    @Operation(summary = "Get bays", description = "List all bays or get a specific bay detail by locationId and bayId.")
    @ApiResponse(responseCode = "200", description = "Bays retrieved successfully.")
    @GetMapping({ "/bays", "/{locationId}/bays/{bayId}" })
    public ResponseEntity<Object> getBays(
            @Parameter(description = "Location ID (optional for specific bay)") @PathVariable(required = false) Long locationId,
            @Parameter(description = "Bay ID (optional for specific bay)") @PathVariable(required = false) Long bayId) {
        log.info("Fetching bays - locationId={}, bayId={}", locationId, bayId);
        Object bays = bayService.getBays(locationId, bayId);
        return ResponseEntity.ok(bays);
    }

    @Operation(summary = "Create bay", description = "Create a new bay for a specific shop location.")
    @ApiResponse(responseCode = "200", description = "Bay created successfully.")
    @PostMapping("/{locationId}/bays")
    public ResponseEntity<Object> createBay(
            @Parameter(description = "Shop location ID", example = "1") @PathVariable Long locationId,
            @Parameter(description = "Bay creation request body") @RequestBody(required = false) Object request) {
        log.info("Creating bay for shop location ID: {}", locationId);
        Object bay = bayService.createBay(locationId, request);
        return ResponseEntity.ok(bay);
    }

    @Operation(summary = "Manage bays", description = "Create or update bays in bulk.")
    @ApiResponse(responseCode = "200", description = "Bays managed successfully.")
    @PutMapping("/bays")
    public ResponseEntity<Object> manageBays(
            @Parameter(description = "Bay management request body") @RequestBody(required = false) Object request) {
        log.info("Managing bays");
        Object result = bayService.manageBays(request);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Delete bay", description = "Delete a specific bay by locationId and bayId.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Bay deleted successfully."),
            @ApiResponse(responseCode = "404", description = "Bay not found.")
    })
    @DeleteMapping("/{locationId}/bays/{bayId}")
    public ResponseEntity<Void> deleteBay(
            @Parameter(description = "Shop location ID", example = "1") @PathVariable Long locationId,
            @Parameter(description = "Bay ID", example = "1") @PathVariable Long bayId) {
        log.info("Deleting bay ID: {} for shop location ID: {}", bayId, locationId);
        bayService.deleteBay(locationId, bayId);
        return ResponseEntity.noContent().build();
    }
}
