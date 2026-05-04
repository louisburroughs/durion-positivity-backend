package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.DeactivateLocationRequest;
import com.positivity.inventory.internal.dto.DeactivateLocationResponse;
import com.positivity.inventory.service.InventoryLocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/locations")
@Tag(name = "Inventory Management", description = "Operations related to inventory location management")
public class InventoryLocationDeactivationController {

        private final InventoryLocationService service;

        public InventoryLocationDeactivationController(InventoryLocationService service) {
                this.service = service;
        }

        /**
         * Deactivate a storage location.
         */
        @PostMapping("/{locationId}/deactivate")
        @EmitEvent(id = "INVENTORY_LOCATION_DEACTIVATE", apiVersion = "1")
        @PreAuthorize("hasAuthority('inventory:location:admin')")
        @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth", scopes = {
                        "inventory:location:admin" })
        @Operation(summary = "Deactivate a storage location", description = "Deactivate a storage location with atomic stock transfer to a destination location (Option B). "
                        + "If the location contains active inventory, a destination location must be specified.", tags = {
                                        "Inventory Management" })
        @ApiResponse(responseCode = "200", description = "Deactivation completed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = DeactivateLocationResponse.class)))
        @ApiResponse(responseCode = "400", description = "Bad request - invalid parameters or destination required")
        @ApiResponse(responseCode = "404", description = "Location not found")
        @ApiResponse(responseCode = "409", description = "Conflict - business rule violation")
        public ResponseEntity<DeactivateLocationResponse> deactivate(
                        @Parameter(description = "Location ID to deactivate", required = true) @PathVariable("locationId") UUID locationId,
                        @RequestBody(required = false) DeactivateLocationRequest body) {
                DeactivateLocationResponse resp = service.deactivateLocation(locationId,
                                body != null ? body.getDestinationLocationId() : null);
                return ResponseEntity.ok(resp);
        }
}
