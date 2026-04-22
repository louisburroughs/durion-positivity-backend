package com.positivity.location.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.SiteDefaultsRequest;
import com.positivity.location.internal.dto.SiteDefaultsResponse;
import com.positivity.location.service.SiteDefaultsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/locations/{locationId}/defaults")
@RequiredArgsConstructor
@Tag(name = "Site Defaults API", description = "Operations for managing location site default settings")
public class SiteDefaultsController {

        private final SiteDefaultsService siteDefaultsService;

        @PutMapping
        @Operation(summary = "Configure site defaults", description = "Create or update default site configuration for a location.")
        @ApiResponse(responseCode = "200", description = "Site defaults configured", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SiteDefaultsResponse.class)))
        @ApiResponse(responseCode = "400", description = "Invalid request payload")
        @ApiResponse(responseCode = "403", description = "Forbidden")
        @ApiResponse(responseCode = "404", description = "Location not found")
        @PreAuthorize("hasAuthority('location:write')")
        @EmitEvent(id = "LOCATION_SITE_DEFAULTS_PUT", apiVersion = "1")
        public ResponseEntity<SiteDefaultsResponse> configureDefaults(
                        @Parameter(description = "ID of the location", required = true) @PathVariable UUID locationId,
                        @RequestBody SiteDefaultsRequest request) {
                return ResponseEntity.ok(siteDefaultsService.configureDefaults(locationId, request));
        }

        @GetMapping
        @Operation(summary = "Get site defaults", description = "Retrieve default site configuration for a location.")
        @ApiResponse(responseCode = "200", description = "Site defaults returned", content = @Content(mediaType = "application/json", schema = @Schema(implementation = SiteDefaultsResponse.class)))
        @ApiResponse(responseCode = "403", description = "Forbidden")
        @ApiResponse(responseCode = "404", description = "Location not found")
        @PreAuthorize("hasAuthority('location:read')")
        @EmitEvent(id = "LOCATION_SITE_DEFAULTS_GET", apiVersion = "1")
        public ResponseEntity<SiteDefaultsResponse> getDefaults(
                        @Parameter(description = "ID of the location", required = true) @PathVariable UUID locationId) {
                return ResponseEntity.ok(siteDefaultsService.getDefaults(locationId));
        }
}
