package com.positivity.vehicle.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.vehicle.internal.dto.UpsertPreferencesRequest;
import com.positivity.vehicle.internal.dto.VehicleCarePreferenceMapper;
import com.positivity.vehicle.internal.dto.VehicleCarePreferenceResponse;
import com.positivity.vehicle.service.VehiclePreferencesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for vehicle care preferences (CAP:091 Story #102).
 * Provides flexible JSONB-based preference management.
 */
@Slf4j
@Tag(name = "Vehicle Preferences", description = "Manage vehicle care preferences with flexible schema support")
@RequiredArgsConstructor
@RestController
@PreAuthorize("isAuthenticated()")
@RequestMapping("/v1/vehicles/{vehicleId}/preferences")
public class VehiclePreferencesController {

    private final VehiclePreferencesService preferencesService;

    @Operation(
            summary = "Get vehicle care preferences",
            description = "Retrieves the care preferences for a vehicle. Returns 404 if no preferences exist.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Preferences found and returned",
                content = @Content(schema = @Schema(implementation = VehicleCarePreferenceResponse.class))),
        @ApiResponse(responseCode = "404", description = "No preferences found for this vehicle")
    })
    @GetMapping
    public ResponseEntity<VehicleCarePreferenceResponse> getPreferences(
            @Parameter(description = "Vehicle ID") @PathVariable UUID vehicleId) {

        log.info("GET /v1/vehicles/{}/preferences", vehicleId);
        return preferencesService
                .getPreferences(vehicleId)
                .map(VehicleCarePreferenceMapper::toResponse)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "Create or update vehicle care preferences",
            description =
                    "Upserts preferences for a vehicle. If preferences exist, replaces them entirely. Use PATCH for partial updates.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Preferences updated successfully",
                content = @Content(schema = @Schema(implementation = VehicleCarePreferenceResponse.class))),
        @ApiResponse(
                responseCode = "201",
                description = "Preferences created successfully",
                content = @Content(schema = @Schema(implementation = VehicleCarePreferenceResponse.class))),
        @ApiResponse(responseCode = "404", description = "Vehicle not found"),
        @ApiResponse(responseCode = "400", description = "Invalid preference data")
    })
    @PutMapping
    @EmitEvent(id = "VEHICLE_PREFERENCES_UPSERT", apiVersion = "1")
    public ResponseEntity<VehicleCarePreferenceResponse> upsertPreferences(
            @Parameter(description = "Vehicle ID") @PathVariable UUID vehicleId,
            @RequestBody PreferencesUpsertDto request) {

        log.info(
                "PUT /v1/vehicles/{}/preferences - keys={}",
                vehicleId,
                request.preferences().keySet());

        var serviceRequest = new UpsertPreferencesRequest(
                vehicleId,
                request.preferences(),
                request.serviceNotes(),
                request.createdByUserId(),
                request.updatedByUserId());

        var preference = preferencesService.upsertPreferences(serviceRequest);
        return ResponseEntity.ok(VehicleCarePreferenceMapper.toResponse(preference));
    }

    @Operation(
            summary = "Partially update vehicle care preferences",
            description =
                    "Merges provided preference fields into existing preferences without replacing the entire map")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Preferences merged successfully",
                content = @Content(schema = @Schema(implementation = VehicleCarePreferenceResponse.class))),
        @ApiResponse(responseCode = "404", description = "No existing preferences to merge into")
    })
    @PatchMapping
    @EmitEvent(id = "VEHICLE_PREFERENCES_MERGE", apiVersion = "1")
    public ResponseEntity<VehicleCarePreferenceResponse> mergePreferences(
            @Parameter(description = "Vehicle ID") @PathVariable UUID vehicleId,
            @RequestBody PreferencesMergeDto request) {

        log.info(
                "PATCH /v1/vehicles/{}/preferences - merging keys={}",
                vehicleId,
                request.partialPreferences().keySet());

        var preference =
                preferencesService.mergePreferences(vehicleId, request.partialPreferences(), request.updatedByUserId());
        return ResponseEntity.ok(VehicleCarePreferenceMapper.toResponse(preference));
    }

    @Operation(summary = "Delete vehicle care preferences", description = "Removes all preferences for a vehicle")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Preferences deleted successfully"),
        @ApiResponse(responseCode = "404", description = "No preferences found to delete")
    })
    @DeleteMapping
    @EmitEvent(id = "VEHICLE_PREFERENCES_DELETE", apiVersion = "1")
    public ResponseEntity<Void> deletePreferences(@Parameter(description = "Vehicle ID") @PathVariable UUID vehicleId) {

        log.info("DELETE /v1/vehicles/{}/preferences", vehicleId);
        preferencesService.deletePreferences(vehicleId);
        return ResponseEntity.noContent().build();
    }

    /**
     * DTO for upserting preferences.
     */
    public record PreferencesUpsertDto(
            Map<String, Object> preferences, String serviceNotes, UUID createdByUserId, UUID updatedByUserId) {}

    /**
     * DTO for merging preferences.
     */
    public record PreferencesMergeDto(Map<String, Object> partialPreferences, UUID updatedByUserId) {}
}
