package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/inventory/sites")
@Tag(name = "Inventory Sites", description = "Site inventory configuration endpoints. Inventory Sites represent physical locations that hold inventory.")
public class InventorySiteDefaultLocationsController {

        @GetMapping("/{siteId}/defaultLocations")
        @PreAuthorize("hasAuthority('inventory:location:view')")
        @Operation(summary = "Get site default locations", description = "Returns configured default locations for a site. Stub implementation.")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "200", description = "Default locations returned", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = UUID.class)))),
                        @ApiResponse(responseCode = "501", description = "Not implemented")
        })
        public ResponseEntity<List<UUID>> getSiteDefaultLocations(
                        @Parameter(description = "Site identifier", required = true) @PathVariable UUID siteId) {
                log.info("GET /v1/inventory/sites/{}/defaultLocations", siteId);
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }

        @PutMapping("/{siteId}/defaultLocations")
        @EmitEvent(id = "INVENTORY_SITE_DEFAULT_LOCATIONS_UPDATE", apiVersion = "1")
        @PreAuthorize("hasAuthority('inventory:location:admin')")
        @Operation(summary = "Replace site default locations", description = "Replaces the configured default locations for a site. Stub implementation.")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "200", description = "Default locations replaced"),
                        @ApiResponse(responseCode = "501", description = "Not implemented")
        })
        public ResponseEntity<Void> putSiteDefaultLocations(
                        @Parameter(description = "Site identifier", required = true) @PathVariable UUID siteId,
                        @RequestBody(required = false) List<UUID> defaultLocationIds) {
                log.info("PUT /v1/inventory/sites/{}/defaultLocations", siteId);
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }
}
