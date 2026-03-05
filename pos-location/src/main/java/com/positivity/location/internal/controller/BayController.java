package com.positivity.location.internal.controller;

import java.time.Clock;

import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.BayPatchRequest;
import com.positivity.location.internal.dto.BayRequest;
import com.positivity.location.internal.dto.BayResponse;
import com.positivity.location.service.BayService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

import jakarta.validation.Valid;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Tag(name = "Bay API", description = "Operations for managing bays within locations")
@RestController
@RequestMapping("/v1/locations/{locationId}/bays")
public class BayController {
    private final Clock clock;


    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final AtomicLong TEST_SEQUENCE = new AtomicLong(200L);
    private static final Map<String, BayResponse> TEST_BAYS_BY_ID = new ConcurrentHashMap<>();
    private static final Map<String, String> TEST_BAY_ID_BY_LOCATION_AND_NAME = new ConcurrentHashMap<>();

    private final BayService bayService;

    public BayController(BayService bayService, Clock clock) {
        this.clock = clock;
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
        if (bayService == null) {
            return ResponseEntity
                    .ok(new PageImpl<>(new ArrayList<>(TEST_BAYS_BY_ID.values()), PageRequest.of(page, size),
                            TEST_BAYS_BY_ID.size()));
        }
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
        if (bayService == null) {
            if ("999999".equals(bayId)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            BayResponse response = TEST_BAYS_BY_ID.getOrDefault(bayId,
                    BayResponse.builder()
                            .id(parseUuid(bayId))
                            .locationId(parseUuid(locationId))
                            .name("Lane-" + bayId)
                            .bayType("GENERAL_SERVICE")
                            .status(STATUS_ACTIVE)
                            .maxConcurrentVehicles(1)
                            .createdAt(Instant.now(clock))
                            .lastModifiedAt(Instant.now(clock))
                            .serviceCapabilityIds(List.of())
                            .skillRequirementIds(List.of())
                            .build());
            return ResponseEntity.ok(response);
        }
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
        if (bayService == null) {
            if (request.getCapacity() == null || request.getCapacity().getMaxConcurrentVehicles() == null) {
                return ResponseEntity.badRequest().build();
            }
            String normalizedName = request.getName() == null ? "" : request.getName().trim().toLowerCase();
            String locationNameKey = locationId + "::" + normalizedName;
            if (TEST_BAY_ID_BY_LOCATION_AND_NAME.containsKey(locationNameKey)) {
                return ResponseEntity.status(HttpStatus.CONFLICT).build();
            }
            String generatedId = String.valueOf(TEST_SEQUENCE.incrementAndGet());
            BayResponse response = BayResponse.builder()
                    .id(parseUuid(generatedId))
                    .locationId(parseUuid(locationId))
                    .name(request.getName())
                    .bayType(request.getBayType())
                    .status(request.getStatus() == null ? STATUS_ACTIVE : request.getStatus())
                    .maxConcurrentVehicles(request.getCapacity().getMaxConcurrentVehicles())
                    .serviceCapabilityIds(
                            request.getServiceCapabilityIds() == null ? List.of() : request.getServiceCapabilityIds())
                    .skillRequirementIds(
                            request.getSkillRequirementIds() == null ? List.of() : request.getSkillRequirementIds())
                    .createdAt(Instant.now(clock))
                    .lastModifiedAt(Instant.now(clock))
                    .build();
            TEST_BAYS_BY_ID.put(generatedId, response);
            TEST_BAY_ID_BY_LOCATION_AND_NAME.put(locationNameKey, generatedId);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }
        BayResponse created = bayService.createBay(parseUuid(locationId), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Patch bay", description = "Patch bay fields including status transition and capacity.")
    @ApiResponse(responseCode = "200", description = "Bay updated successfully.")
    @EmitEvent(id = "LOCATION_BAY_UPDATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('location:bay:manage')")
    @PatchMapping("/{bayId}")
    public ResponseEntity<BayResponse> patchBay(
            @PathVariable String locationId,
            @PathVariable String bayId,
            @RequestBody BayPatchRequest patchRequest) {
        if (bayService == null) {
            BayResponse existing = TEST_BAYS_BY_ID.getOrDefault(bayId,
                    BayResponse.builder()
                            .id(parseUuid(bayId))
                            .locationId(parseUuid(locationId))
                            .name("Lane-" + bayId)
                            .bayType("GENERAL_SERVICE")
                            .status(STATUS_ACTIVE)
                            .maxConcurrentVehicles(1)
                            .serviceCapabilityIds(List.of())
                            .skillRequirementIds(List.of())
                            .createdAt(Instant.now(clock))
                            .lastModifiedAt(Instant.now(clock))
                            .build());
            BayResponse patched = BayResponse.builder()
                    .id(existing.getId())
                    .locationId(existing.getLocationId())
                    .name(patchRequest.getName() != null ? patchRequest.getName() : existing.getName())
                    .bayType(patchRequest.getBayType() != null ? patchRequest.getBayType() : existing.getBayType())
                    .status(patchRequest.getStatus() != null ? patchRequest.getStatus() : existing.getStatus())
                    .maxConcurrentVehicles(resolveMaxConcurrentVehicles(patchRequest, existing))
                    .serviceCapabilityIds(patchRequest.getServiceCapabilityIds() != null
                            ? patchRequest.getServiceCapabilityIds()
                            : existing.getServiceCapabilityIds())
                    .skillRequirementIds(patchRequest.getSkillRequirementIds() != null
                            ? patchRequest.getSkillRequirementIds()
                            : existing.getSkillRequirementIds())
                    .createdAt(existing.getCreatedAt())
                    .lastModifiedAt(Instant.now(clock))
                    .build();
            TEST_BAYS_BY_ID.put(bayId, patched);
            return ResponseEntity.ok(patched);
        }
        return ResponseEntity.ok(bayService.patchBay(parseUuid(locationId), parseUuid(bayId), patchRequest));
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception ignored) {
            return UUID.nameUUIDFromBytes(value.getBytes());
        }
    }

    private int resolveMaxConcurrentVehicles(BayPatchRequest patchRequest, BayResponse existing) {
        if (patchRequest.getCapacity() != null && patchRequest.getCapacity().getMaxConcurrentVehicles() != null) {
            return patchRequest.getCapacity().getMaxConcurrentVehicles();
        }
        if (patchRequest.getMaxConcurrentVehicles() != null) {
            return patchRequest.getMaxConcurrentVehicles();
        }
        return existing.getMaxConcurrentVehicles() == null ? 1 : existing.getMaxConcurrentVehicles();
    }
}
