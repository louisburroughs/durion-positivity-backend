package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
@RequestMapping("/v1/inventory/sites")
@Tag(
        name = "Inventory Sites",
        description =
                "Site inventory configuration endpoints. Inventory Sites represent physical locations that hold inventory.")
public class InventorySiteDefaultLocationsController {

    @GetMapping("/{siteId}/defaultLocations")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:location:view"})
    @PreAuthorize("hasAuthority('inventory:location:view')")
    @Operation(
            operationId = "getSiteDefaultLocations",
            summary = "Get site default locations",
            description = """
                    Returns the configured default storage locations for a site; this endpoint is a stub that \
                    currently always answers 501 Not Implemented.
                    Use this tool only to probe the future site-defaults contract; use getSiteInventoryRollup \
                    instead for actual site inventory data, and do not expect updateSiteDefaultLocations to have \
                    stored anything readable here.
                    Preconditions: none are evaluated; no configuration store exists yet.
                    Required inputs: siteId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; the stub performs no lookup.
                    Returns 501 unconditionally until the configuration store is implemented.
                    """,
            tags = {"Inventory Sites"})
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Default locations returned",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        array = @ArraySchema(schema = @Schema(implementation = UUID.class)))),
                @ApiResponse(responseCode = "501", description = "Not implemented")
            })
    public ResponseEntity<List<UUID>> getSiteDefaultLocations(
            @Parameter(description = "Site identifier", required = true) @PathVariable UUID siteId) {
        log.info("GET /v1/inventory/sites/{}/defaultLocations", siteId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    @PutMapping("/{siteId}/defaultLocations")
    @EmitEvent(id = "INVENTORY_SITE_DEFAULT_LOCATIONS_UPDATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:location:admin"})
    @PreAuthorize("hasAuthority('inventory:location:admin')")
    @Operation(
            operationId = "updateSiteDefaultLocations",
            summary = "Replace site default locations",
            description = """
                    Replaces the configured default storage locations for a site; this endpoint is a stub that \
                    currently always answers 501 Not Implemented.
                    Use this tool only to probe the future site-defaults contract; use getSiteDefaultLocations \
                    instead to read the configuration once implemented.
                    Preconditions: none are evaluated; no configuration store exists yet.
                    Required inputs: siteId (UUID) path parameter and an optional JSON array of storage-location \
                    UUIDs as the body.
                    Emits an INVENTORY_SITE_DEFAULT_LOCATIONS_UPDATE event when invoked; no configuration is \
                    stored.
                    Returns 501 unconditionally until the configuration store is implemented.
                    """,
            tags = {"Inventory Sites"})
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "200", description = "Default locations replaced"),
                @ApiResponse(responseCode = "501", description = "Not implemented")
            })
    public ResponseEntity<Void> putSiteDefaultLocations(
            @Parameter(description = "Site identifier", required = true) @PathVariable UUID siteId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Storage-location ids to install as the site's defaults, replacing any"
                                    + " previous configuration.",
                            required = false,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Two default locations", value = """
                                                                    ["018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01",
                                                                     "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02"]
                                                                    """)))
                    @RequestBody(required = false)
                    List<UUID> defaultLocationIds) {
        log.info("PUT /v1/inventory/sites/{}/defaultLocations", siteId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
