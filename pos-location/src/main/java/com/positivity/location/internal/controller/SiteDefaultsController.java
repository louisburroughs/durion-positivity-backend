package com.positivity.location.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.SiteDefaultsRequest;
import com.positivity.location.internal.dto.SiteDefaultsResponse;
import com.positivity.location.service.SiteDefaultsService;
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
public class SiteDefaultsController {

    private final SiteDefaultsService siteDefaultsService;

    @PutMapping
    @PreAuthorize("hasAuthority('location:write')")
    @EmitEvent(id = "LOCATION_SITE_DEFAULTS_PUT", apiVersion = "1")
    public ResponseEntity<SiteDefaultsResponse> configureDefaults(@PathVariable UUID locationId,
            @RequestBody SiteDefaultsRequest request) {
        return ResponseEntity.ok(siteDefaultsService.configureDefaults(locationId, request));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('location:read')")
    @EmitEvent(id = "LOCATION_SITE_DEFAULTS_GET", apiVersion = "1")
    public ResponseEntity<SiteDefaultsResponse> getDefaults(@PathVariable UUID locationId) {
        return ResponseEntity.ok(siteDefaultsService.getDefaults(locationId));
    }
}
