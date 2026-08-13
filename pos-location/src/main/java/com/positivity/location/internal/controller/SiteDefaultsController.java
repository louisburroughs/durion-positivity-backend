package com.positivity.location.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.SiteDefaultsRequest;
import com.positivity.location.internal.dto.SiteDefaultsResponse;
import com.positivity.location.service.SiteDefaultsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for site default storage location endpoints.
 *
 * Issue: CAP-214 #38
 */
@RestController
@RequestMapping("/v1/locations/{locationId}/defaults")
@RequiredArgsConstructor
@Tag(name = "Site Defaults API", description = "Operations for managing location site default settings")
public class SiteDefaultsController {

    private final SiteDefaultsService siteDefaultsService;

    @PutMapping
    @Operation(
            operationId = "configureSiteDefaults",
            summary = "Configure Default Storage Locations for Site",
            description = """
                    Creates or replaces the default staging and quarantine storage locations of a site in a \
                    single idempotent upsert.
                    Use this tool when commissioning a site's receiving flow or moving its defaults; use \
                    getSiteDefaults instead to read the current assignment.
                    Preconditions: the site must exist, and both referenced storage locations must belong to that \
                    site.
                    Required inputs: locationId (UUID) as a path parameter and a body with both \
                    defaultStagingLocationId and defaultQuarantineLocationId; the two ids must differ.
                    Emits a LOCATION_SITE_DEFAULTS_PUT event and republishes the location fact carrying the new \
                    defaults.
                    Returns 404 when the site does not exist, 400 when either id is missing or both ids are the \
                    same, and 422 when a referenced storage location does not belong to the site.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Site defaults configured",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SiteDefaultsResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request payload")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    @ApiResponse(responseCode = "404", description = "Location not found")
    @ApiResponse(responseCode = "422", description = "Default storage location does not belong to the site")
    @PreAuthorize("hasAuthority('location:write')")
    @EmitEvent(id = "LOCATION_SITE_DEFAULTS_PUT", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:write"})
    public ResponseEntity<SiteDefaultsResponse> configureDefaults(
            @Parameter(description = "ID of the location", required = true) @PathVariable UUID locationId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Pair of storage location ids to install as the site's staging and"
                                    + " quarantine defaults.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Staging and quarantine defaults",
                                                            value = """
                                                                    {"defaultStagingLocationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01",
                                                                     "defaultQuarantineLocationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02"}
                                                                    """)))
                    @RequestBody
                    SiteDefaultsRequest request) {
        return ResponseEntity.ok(siteDefaultsService.configureDefaults(locationId, request));
    }

    @GetMapping
    @Operation(operationId = "getSiteDefaults", summary = "Get Default Storage Locations for Site", description = """
                    Returns the default staging and quarantine storage location ids configured for a site.
                    Use this tool when routing received or quarantined inventory; do not use \
                    configureSiteDefaults, which overwrites the assignment.
                    Preconditions: the site must exist; both ids are null when defaults were never configured.
                    Required inputs: locationId (UUID) as a path parameter.
                    Emits a LOCATION_SITE_DEFAULTS_GET event; no state changes.
                    Returns 404 when the site does not exist.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Site defaults returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SiteDefaultsResponse.class)))
    @ApiResponse(responseCode = "403", description = "Forbidden")
    @ApiResponse(responseCode = "404", description = "Location not found")
    @PreAuthorize("hasAuthority('location:read')")
    @EmitEvent(id = "LOCATION_SITE_DEFAULTS_GET", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"location:read"})
    public ResponseEntity<SiteDefaultsResponse> getDefaults(
            @Parameter(description = "ID of the location", required = true) @PathVariable UUID locationId) {
        return ResponseEntity.ok(siteDefaultsService.getDefaults(locationId));
    }
}
