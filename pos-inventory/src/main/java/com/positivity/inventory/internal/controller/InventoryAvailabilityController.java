package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.AvailabilityView;
import com.positivity.inventory.internal.dto.InventoryErrorResponse;
import com.positivity.inventory.internal.dto.InventoryAvailabilityResponse;
import com.positivity.inventory.internal.dto.LeadTimeView;
import com.positivity.inventory.internal.dto.LocationAvailabilityDto;
import com.positivity.inventory.service.InventoryAvailabilityService;
import com.positivity.inventory.service.InventoryLeadTimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/v1/inventory/availability")
@Tag(name = "Inventory Availability", description = "Inventory availability read/write endpoints")
@PreAuthorize("hasAnyAuthority('inventory:availability:read','inventory:adjustment:approve','inventory:adjustment:create')")
public class InventoryAvailabilityController {

    private final InventoryAvailabilityService availabilityService;
    private final InventoryLeadTimeService inventoryLeadTimeService;

    public InventoryAvailabilityController(
            InventoryAvailabilityService availabilityService,
            InventoryLeadTimeService inventoryLeadTimeService) {
        this.availabilityService = availabilityService;
        this.inventoryLeadTimeService = inventoryLeadTimeService;
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

    @GetMapping("/query")
    @EmitEvent(id = "INVENTORY_AVAILABILITY_QUERY", apiVersion = "1")
    @Operation(summary = "Query inventory availability by SKU and location", description = "Returns on-hand, allocated, and available-to-promise quantities for a product at a specific location. storageLocationId is optional to narrow the scope to a sub-location.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Availability view returned", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AvailabilityView.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request parameters", content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "User lacks required read permission", content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Product SKU or location not found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryErrorResponse.class)))
    })
    // Issue: CAP-215 Story #36
    public ResponseEntity<AvailabilityView> queryAvailabilityBySku(
            @Parameter(description = "Product SKU", required = true) @RequestParam String productSku,
            @Parameter(description = "Location identifier", required = true) @RequestParam UUID locationId,
            @Parameter(description = "Storage location identifier (optional; narrows to sub-location)") @RequestParam(required = false) UUID storageLocationId) {
        log.info("GET /v1/inventory/availability/query productSku={} locationId={}", productSku, locationId);
        return ResponseEntity.ok(
                availabilityService.queryAvailability(productSku, locationId, storageLocationId));
    }

    @GetMapping("/lead-time")
    @EmitEvent(id = "INVENTORY_LEAD_TIME_QUERY", apiVersion = "1")
    @Operation(summary = "Query product lead time", description = "Returns dynamic lead-time estimate for a product at a location.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Lead-time view returned", content = @Content(mediaType = "application/json", schema = @Schema(implementation = LeadTimeView.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request parameters", content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "User lacks required read permission", content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Lead-time data not found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error", content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryErrorResponse.class)))
    })
    public ResponseEntity<LeadTimeView> queryLeadTime(
            @Parameter(description = "Product identifier", required = true) @RequestParam UUID productId,
            @Parameter(description = "Location identifier", required = true) @RequestParam UUID locationId) {
        log.info("GET /v1/inventory/availability/lead-time productId={} locationId={}", productId, locationId);
        return ResponseEntity.ok(inventoryLeadTimeService.queryLeadTime(productId, locationId));
    }

    /**
     * Not implemented by design.
     *
     * <p>Availability is a derived projection computed from inventory ledger events, not a mutable
     * record that can be overwritten directly. Accepting direct writes here would bypass movement
     * validation, break auditability, and risk ATP inconsistencies.
     *
     * <p>Use ledger-backed write APIs instead:
     * POST /v1/inventory/stock-movements
     * POST /v1/inventory/adjustments
     */
    @PostMapping("/{productId}")
    @EmitEvent(id = "INVENTORY_AVAILABILITY_UPDATE", apiVersion = "1")
    @Operation(
            summary = "Update inventory availability",
            description = "Not implemented by design. Availability is derived from ledger events and is read-only via this endpoint. "
                    + "Use POST /v1/inventory/stock-movements or POST /v1/inventory/adjustments for inventory changes.")
    @ApiResponse(responseCode = "200", description = "Availability updated", content = @Content(mediaType = "application/json", schema = @Schema(implementation = InventoryAvailabilityResponse.class)))
    @ApiResponse(responseCode = "501", description = "Not implemented")
    public ResponseEntity<InventoryAvailabilityResponse> updateInventoryAvailability(
            @Parameter(description = "Product identifier", required = true) @PathVariable UUID productId,
            @RequestBody(required = false) Object requestBody) {
        log.info("POST /v1/inventory/availability/{}", productId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
