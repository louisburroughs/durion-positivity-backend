package com.positivity.location.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.BayPatchRequest;
import com.positivity.location.internal.dto.BayRequest;
import com.positivity.location.internal.dto.BayResponse;
import com.positivity.location.service.BayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "Bay API", description = "Operations for managing bays within locations")
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/locations/{locationId}/bays")
public class BayController {
    private final BayService bayService;

    public BayController(BayService bayService) {
        this.bayService = bayService;
    }

    @Operation(summary = "List bays", description = "List bays for a location with optional status and bayType filters.")
    @ApiResponse(responseCode = "200", description = "Bays retrieved successfully.")
    @PreAuthorize("hasAuthority('location:bay:read')")
    @GetMapping
    public ResponseEntity<Page<BayResponse>> listBays(
            @Parameter(description = "Location ID") @PathVariable String locationId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String bayType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(bayService.listBays(parseUuid(locationId), status, bayType, pageable));
    }

    @Operation(summary = "Get bay", description = "Get a bay by location and bay id.")
    @ApiResponse(responseCode = "200", description = "Bay retrieved successfully.")
    @ApiResponse(responseCode = "404", description = "Bay not found.")
    @PreAuthorize("hasAuthority('location:bay:read')")
    @GetMapping("/{bayId}")
    public ResponseEntity<BayResponse> getBay(
            @Parameter(description = "Location ID") @PathVariable String locationId,
            @Parameter(description = "Bay ID") @PathVariable String bayId) {
        return ResponseEntity.ok(bayService.getBay(parseUuid(locationId), parseUuid(bayId)));
    }

    @Operation(summary = "Create bay", description = "Create a new bay for a specific location.")
    @ApiResponse(responseCode = "201", description = "Bay created successfully.")
    @EmitEvent(id = "LOCATION_BAY_CREATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('location:bay:manage')")
    @PostMapping
    public ResponseEntity<BayResponse> createBay(
            @Parameter(description = "Location ID", example = "1") @PathVariable String locationId,
            @Parameter(description = "Bay creation request body") @Valid @RequestBody BayRequest request) {
        BayResponse created = bayService.createBay(parseUuid(locationId), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Patch bay", description = "Patch bay fields including status transition and capacity.")
    @ApiResponse(responseCode = "200", description = "Bay updated successfully.")
    @EmitEvent(id = "LOCATION_BAY_UPDATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('location:bay:manage')")
    @PatchMapping("/{bayId}")
    public ResponseEntity<BayResponse> patchBay(
            @PathVariable String locationId, @PathVariable String bayId, @RequestBody BayPatchRequest patchRequest) {
        return ResponseEntity.ok(bayService.patchBay(parseUuid(locationId), parseUuid(bayId), patchRequest));
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception ignored) {
            return UUID.nameUUIDFromBytes(value.getBytes());
        }
    }
}
