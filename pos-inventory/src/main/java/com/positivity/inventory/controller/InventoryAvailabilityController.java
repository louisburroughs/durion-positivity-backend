package com.positivity.inventory.controller;

import com.positivity.inventory.dto.InventoryAvailabilityResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/v1/inventory/availability")
@Tag(name = "Inventory Availability", description = "Inventory availability read/write endpoints")
public class InventoryAvailabilityController {

    @GetMapping("/{productId}")
    @Operation(summary = "Query inventory availability", description = "Returns availability for a product. Stub implementation.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Availability returned", content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryAvailabilityResponse.class))),
            @ApiResponse(responseCode = "501", description = "Not implemented")
    })
    public ResponseEntity<InventoryAvailabilityResponse> queryInventoryAvailability(
            @Parameter(description = "Product identifier", required = true) @PathVariable UUID productId) {
        log.info("GET /v1/inventory/availability/{}", productId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PostMapping("/{productId}")
    @Operation(summary = "Update inventory availability", description = "Updates availability for a product. Stub implementation.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Availability updated", content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryAvailabilityResponse.class))),
            @ApiResponse(responseCode = "501", description = "Not implemented")
    })
    public ResponseEntity<InventoryAvailabilityResponse> updateInventoryAvailability(
            @Parameter(description = "Product identifier", required = true) @PathVariable UUID productId,
            @RequestBody(required = false) Object requestBody) {
        log.info("POST /v1/inventory/availability/{}", productId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
