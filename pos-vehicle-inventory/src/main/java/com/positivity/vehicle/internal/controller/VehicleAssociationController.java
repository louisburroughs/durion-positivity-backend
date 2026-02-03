package com.positivity.vehicle.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.vehicle.internal.entity.VehiclePartyAssociation;
import com.positivity.vehicle.internal.entity.VehiclePartyAssociation.AssociationType;
import com.positivity.vehicle.service.VehicleAssociationService;
import com.positivity.vehicle.service.VehicleAssociationService.CreateAssociationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST controller for vehicle-party associations (CAP:091 Story #104).
 * Provides temporal association management with implicit owner reassignment.
 */
@Slf4j
@Tag(name = "Vehicle Associations", description = "Manage vehicle-party associations with temporal tracking")
@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/vehicles/{vehicleId}/associations")
public class VehicleAssociationController {

    private final VehicleAssociationService associationService;

    @Operation(summary = "Create vehicle-party association", description = "Creates a new association between a vehicle and a party. For OWNER type, automatically ends existing owner association.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Association created successfully", content = @Content(schema = @Schema(implementation = VehiclePartyAssociation.class))),
            @ApiResponse(responseCode = "404", description = "Vehicle not found"),
            @ApiResponse(responseCode = "400", description = "Invalid request")
    })
    @PostMapping
    @EmitEvent(id = "VEHICLE_ASSOCIATION_CREATE", apiVersion = "1")
    public ResponseEntity<VehiclePartyAssociation> createAssociation(
            @Parameter(description = "Vehicle ID") @PathVariable UUID vehicleId,
            @RequestBody AssociationCreateDto request) {

        log.info("POST /v1/vehicles/{}/associations - type={}, partyId={}",
                vehicleId, request.associationType(), request.partyId());

        var serviceRequest = new CreateAssociationRequest(
                vehicleId,
                request.partyId(),
                request.associationType(),
                request.effectiveStartDate());

        var association = associationService.createAssociation(serviceRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(association);
    }

    @Operation(summary = "Get active associations", description = "Retrieves all currently active associations for a vehicle (where effectiveEndDate is NULL)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Active associations returned"),
            @ApiResponse(responseCode = "404", description = "Vehicle not found")
    })
    @GetMapping
    public ResponseEntity<List<VehiclePartyAssociation>> getActiveAssociations(
            @Parameter(description = "Vehicle ID") @PathVariable UUID vehicleId) {

        log.info("GET /v1/vehicles/{}/associations - fetching active", vehicleId);
        var associations = associationService.getActiveAssociations(vehicleId);
        return ResponseEntity.ok(associations);
    }

    @Operation(summary = "Get active association by type", description = "Retrieves the current active association of a specific type (OWNER, PRIMARY_DRIVER, etc.)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Association found and returned"),
            @ApiResponse(responseCode = "404", description = "No active association of this type found")
    })
    @GetMapping("/type/{type}")
    public ResponseEntity<VehiclePartyAssociation> getActiveAssociationByType(
            @Parameter(description = "Vehicle ID") @PathVariable UUID vehicleId,
            @Parameter(description = "Association type") @PathVariable AssociationType type) {

        log.info("GET /v1/vehicles/{}/associations/type/{}", vehicleId, type);
        return associationService.getActiveAssociationByType(vehicleId, type)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Get historical associations", description = "Retrieves associations of a specific type that were active at a specific point in time")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Historical associations returned")
    })
    @GetMapping("/history")
    public ResponseEntity<List<VehiclePartyAssociation>> getAssociationsAsOf(
            @Parameter(description = "Vehicle ID") @PathVariable UUID vehicleId,
            @Parameter(description = "Association type") @RequestParam AssociationType type,
            @Parameter(description = "Point-in-time query date (ISO-8601)") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOfDate) {

        log.info("GET /v1/vehicles/{}/associations/history?type={}&asOfDate={}", vehicleId, type, asOfDate);
        var associations = associationService.getAssociationsAsOf(vehicleId, type, asOfDate);
        return ResponseEntity.ok(associations);
    }

    @Operation(summary = "Reassign vehicle owner", description = "Atomically ends current owner association and creates new one with the specified party")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Owner reassigned successfully", content = @Content(schema = @Schema(implementation = VehiclePartyAssociation.class))),
            @ApiResponse(responseCode = "404", description = "Vehicle not found")
    })
    @PutMapping("/owner")
    @EmitEvent(id = "VEHICLE_OWNER_REASSIGN", apiVersion = "1")
    public ResponseEntity<VehiclePartyAssociation> reassignOwner(
            @Parameter(description = "Vehicle ID") @PathVariable UUID vehicleId,
            @RequestBody OwnerReassignmentDto request) {

        log.info("PUT /v1/vehicles/{}/associations/owner - newOwnerId={}",
                vehicleId, request.newOwnerId());

        var association = associationService.reassignOwner(
                vehicleId,
                request.newOwnerId(),
                request.effectiveDate());
        return ResponseEntity.ok(association);
    }

    @Operation(summary = "End an association", description = "Sets the effectiveEndDate for an association, marking it as no longer active")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Association ended successfully"),
            @ApiResponse(responseCode = "404", description = "Association not found")
    })
    @DeleteMapping("/{associationId}")
    @EmitEvent(id = "VEHICLE_ASSOCIATION_END", apiVersion = "1")
    public ResponseEntity<Void> endAssociation(
            @Parameter(description = "Vehicle ID") @PathVariable UUID vehicleId,
            @Parameter(description = "Association ID") @PathVariable UUID associationId,
            @Parameter(description = "End date (defaults to now)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate) {

        log.info("DELETE /v1/vehicles/{}/associations/{} - endDate={}",
                vehicleId, associationId, endDate);

        associationService.endAssociation(associationId, endDate);
        return ResponseEntity.noContent().build();
    }

    /**
     * DTO for creating associations.
     */
    public record AssociationCreateDto(
            UUID partyId,
            AssociationType associationType,
            Instant effectiveStartDate) {
    }

    /**
     * DTO for owner reassignment.
     */
    public record OwnerReassignmentDto(
            UUID newOwnerId,
            Instant effectiveDate) {
    }
}
