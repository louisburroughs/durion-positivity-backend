package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.LocationAvailabilityDto;
import com.positivity.inventory.internal.dto.InventoryAvailabilityResponse;
import com.positivity.inventory.service.InventoryAvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/v1/inventory/availability")
@Tag(name = "Inventory Availability", description = "Inventory availability read/write endpoints")
public class InventoryAvailabilityController {

    private final InventoryAvailabilityService availabilityService;

    public InventoryAvailabilityController(InventoryAvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    @GetMapping("/{productId}")
    @Operation(summary = "Query inventory availability", description = "Returns per-location availability for a product.")
    @ApiResponse(responseCode = "200", description = "Availability returned", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = LocationAvailabilityDto.class))))
    @ApiResponse(responseCode = "400", description = "Invalid product identifier")
    // Issue #48: Expose on-hand and ATP grouped by location.
    public ResponseEntity<List<LocationAvailabilityDto>> queryInventoryAvailability(
            @Parameter(description = "Product identifier", required = true) @PathVariable UUID productId) {
        log.info("GET /v1/inventory/availability/{}", productId);
        return ResponseEntity.ok(availabilityService.getAvailabilityByProduct(productId));
    }

    @PostMapping("/{productId}")
    @EmitEvent(id = "INVENTORY_AVAILABILITY_UPDATE", apiVersion = "1")
    @Operation(summary = "Update inventory availability", description = "Updates availability for a product. Stub implementation.")
    @ApiResponse(responseCode = "200", description = "Availability updated", content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryAvailabilityResponse.class)))
    @ApiResponse(responseCode = "501", description = "Not implemented")
    public ResponseEntity<InventoryAvailabilityResponse> updateInventoryAvailability(
            @Parameter(description = "Product identifier", required = true) @PathVariable UUID productId,
            @RequestBody(required = false) Object requestBody) {
        log.info("POST /v1/inventory/availability/{}", productId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
