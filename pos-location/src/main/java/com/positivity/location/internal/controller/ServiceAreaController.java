package com.positivity.location.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.ServiceAreaRequest;
import com.positivity.location.internal.dto.ServiceAreaResponse;
import com.positivity.location.service.ServiceAreaService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * REST API for service area management.
 *
 * Issue: #76
 */
@Tag(name = "Service Area API", description = "Operations for managing service areas")
@RestController
@RequestMapping("/v1/service-areas")
@RequiredArgsConstructor
public class ServiceAreaController {

    private final ServiceAreaService serviceAreaService;

    @Operation(summary = "Create service area")
    @ApiResponse(responseCode = "201", description = "Service area created")
    @EmitEvent(id = "LOCATION_SERVICE_AREA_CREATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('location.service-area.manage')")
    @PostMapping
    public ResponseEntity<ServiceAreaResponse> create(@RequestBody ServiceAreaRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(serviceAreaService.create(request));
    }

    @Operation(summary = "List service areas")
    @ApiResponse(responseCode = "200", description = "Service areas listed")
    @PreAuthorize("hasAuthority('location.service-area.read')")
    @GetMapping
    public ResponseEntity<List<ServiceAreaResponse>> list() {
        return ResponseEntity.ok(serviceAreaService.list());
    }

    @Operation(summary = "Patch service area")
    @ApiResponse(responseCode = "200", description = "Service area patched")
    @PreAuthorize("hasAuthority('location.service-area.manage')")
    @PatchMapping("/{id}")
    public ResponseEntity<ServiceAreaResponse> patch(@PathVariable String id, @RequestBody Map<String, Object> patch) {
        return ResponseEntity.ok(serviceAreaService.patch(id, patch));
    }
}
