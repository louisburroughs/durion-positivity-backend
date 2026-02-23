package com.positivity.location.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.LocationRef;
import com.positivity.location.service.LocationRosterService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Alias controller for location roster endpoint used by contract behavior
 * tests.
 *
 * Issue: CAP-214 #40
 */
@RestController
@RequestMapping("/v1/location/locations")
@RequiredArgsConstructor
public class LocationRosterController {

    private final LocationRosterService locationRosterService;

    @PreAuthorize("hasAuthority('location:read')")
    @EmitEvent(id = "LOCATION_ROSTER_GET", apiVersion = "1")
    @GetMapping("/roster")
    public Page<LocationRef> getRoster(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant sinceUpdatedAt,
            Pageable pageable) {
        return locationRosterService.getRoster(status, sinceUpdatedAt, pageable);
    }
}
