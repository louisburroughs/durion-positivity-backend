package com.positivity.vehicle.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.vehicle.internal.dto.UpsertPreferencesRequest;
import com.positivity.vehicle.internal.dto.VehicleCarePreferenceResponse;
import com.positivity.vehicle.internal.security.VehicleInventoryPermissions;
import com.positivity.vehicle.internal.service.VehiclePreferencesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.HashMap;
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
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@PreAuthorize("isAuthenticated()")
@RequestMapping("/v1/vehicles/{vehicleId}/preferences")
public class VehiclePreferencesController {

    private static final String UPSERT_EXAMPLE = """
            {"preferences":{"preferredOil":"5W-30","tireRotationInterval":5000},
             "serviceIntervalMonths":6,
             "serviceNotes":"Customer prefers synthetic oil only",
             "createdByUserId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
             "updatedByUserId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c"}
            """;

    private static final String MERGE_EXAMPLE = """
            {"partialPreferences":{"tireRotationInterval":7500},
             "serviceIntervalMonths":6,
             "updatedByUserId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c"}
            """;

    private final VehiclePreferencesService preferencesService;

    @Operation(operationId = "getVehiclePreferences", summary = "Get vehicle care preferences", description = """
                    Returns the care-preference document for a vehicle, including the free-form preferences map, \
                    the structured service interval in months and any service notes.
                    Use this tool to read what a customer wants for vehicle care; do not use getVehicle, which \
                    returns the registry record itself and carries no preferences.
                    Preconditions: a preference document must already have been stored for the vehicle, because \
                    vehicles start with no preferences.
                    Required inputs: vehicleId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 with a RESOURCE_NOT_FOUND ApiError when no preference document exists for the \
                    vehicle.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Preferences found and returned",
            content = @Content(schema = @Schema(implementation = VehicleCarePreferenceResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No preferences found for this vehicle",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + VehicleInventoryPermissions.PREFERENCES_MANAGE + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {VehicleInventoryPermissions.PREFERENCES_MANAGE})
    @GetMapping
    public ResponseEntity<VehicleCarePreferenceResponse> getPreferences(
            @Parameter(description = "Vehicle ID") @PathVariable UUID vehicleId) {

        log.info("GET /v1/vehicles/{}/preferences", vehicleId);
        return ResponseEntity.ok(preferencesService
                .getPreferences(vehicleId)
                .orElseThrow(() ->
                        new EntityNotFoundException("No care preferences stored for vehicle '" + vehicleId + "'")));
    }

    @Operation(
            operationId = "upsertVehiclePreferences",
            summary = "Create or update vehicle care preferences",
            description = """
                    Creates or fully replaces the care-preference document for a vehicle, storing the preferences \
                    map and the structured service interval.
                    Use this tool to set preferences wholesale; use mergeVehiclePreferences instead for partial \
                    changes, because this operation replaces the entire map and a null serviceIntervalMonths \
                    clears the stored interval override.
                    Preconditions: the vehicle must exist in the registry; an existing preference document is \
                    replaced and a missing one is created.
                    Required inputs: preferences (a map, which may be empty but not null); serviceIntervalMonths \
                    is optional and must be between 1 and 120 months, a legacy serviceIntervalMonths key inside \
                    the map is promoted into the structured column, and serviceNotes, createdByUserId and \
                    updatedByUserId are optional.
                    Emits a VEHICLE_PREFERENCES_UPSERT event and queues a vehicle.care-preference.updated fact for \
                    CRM consumers.
                    Returns 200 with the saved document for both create and replace, 404 with a \
                    RESOURCE_NOT_FOUND ApiError when the vehicle does not exist, and 400 with a VALIDATION_ERROR \
                    ApiError when serviceIntervalMonths is outside 1 to 120.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Preferences updated successfully",
            content = @Content(schema = @Schema(implementation = VehicleCarePreferenceResponse.class)))
    @ApiResponse(
            responseCode = "201",
            description = "Preferences created successfully",
            content = @Content(schema = @Schema(implementation = VehicleCarePreferenceResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Vehicle not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Invalid preference data",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + VehicleInventoryPermissions.PREFERENCES_MANAGE + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {VehicleInventoryPermissions.PREFERENCES_MANAGE})
    @PutMapping
    @EmitEvent(id = "VEHICLE_PREFERENCES_UPSERT", apiVersion = "1")
    public ResponseEntity<VehicleCarePreferenceResponse> upsertPreferences(
            @Parameter(description = "Vehicle ID") @PathVariable UUID vehicleId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Complete care-preference document that replaces whatever is currently"
                                    + " stored for the vehicle.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Full preference document",
                                                            value = UPSERT_EXAMPLE)))
                    @Valid
                    @RequestBody
                    PreferencesUpsertDto request) {

        log.info(
                "PUT /v1/vehicles/{}/preferences - keys={}",
                vehicleId,
                request.preferences().keySet());

        var serviceRequest = new UpsertPreferencesRequest(
                vehicleId,
                request.preferences(),
                request.serviceIntervalMonths(),
                request.serviceNotes(),
                request.createdByUserId(),
                request.updatedByUserId());

        var preference = preferencesService.upsertPreferences(serviceRequest);
        return ResponseEntity.ok(preference);
    }

    @Operation(
            operationId = "mergeVehiclePreferences",
            summary = "Partially update vehicle care preferences",
            description = """
                    Merges the supplied preference keys into a vehicle's existing care-preference document without \
                    replacing the rest of the map.
                    Use this tool for partial changes such as one key or the interval alone; do not use \
                    upsertVehiclePreferences, which replaces the whole map and clears the interval when it is \
                    omitted.
                    Preconditions: a preference document must already exist for the vehicle, because this \
                    operation cannot create one.
                    Required inputs: no body field is individually mandatory; partialPreferences keys overwrite \
                    matching stored keys, serviceIntervalMonths must be between 1 and 120 months and leaves the \
                    stored value unchanged when null, and a legacy serviceIntervalMonths key inside the map is \
                    promoted into the structured column rather than kept in the blob.
                    Emits a VEHICLE_PREFERENCES_MERGE event and queues a vehicle.care-preference.updated fact for \
                    CRM consumers.
                    Returns 200 with the merged document, 404 with a RESOURCE_NOT_FOUND ApiError when no \
                    preference document exists for the vehicle, and 400 with a VALIDATION_ERROR ApiError when an \
                    interval value is out of range or not a whole number.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Preferences merged successfully",
            content = @Content(schema = @Schema(implementation = VehicleCarePreferenceResponse.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No existing preferences to merge into",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PreAuthorize("hasAuthority('" + VehicleInventoryPermissions.PREFERENCES_MANAGE + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {VehicleInventoryPermissions.PREFERENCES_MANAGE})
    @PatchMapping
    @EmitEvent(id = "VEHICLE_PREFERENCES_MERGE", apiVersion = "1")
    public ResponseEntity<VehicleCarePreferenceResponse> mergePreferences(
            @Parameter(description = "Vehicle ID") @PathVariable UUID vehicleId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Subset of preference fields to merge into the existing care-preference"
                                    + " document.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Partial preference merge",
                                                            value = MERGE_EXAMPLE)))
                    @Valid
                    @RequestBody
                    PreferencesMergeDto request) {

        // partialPreferences is optional: a PATCH may update only serviceIntervalMonths.
        Map<String, Object> partialPreferences =
                request.partialPreferences() != null ? request.partialPreferences() : new HashMap<>();
        log.info("PATCH /v1/vehicles/{}/preferences - merging keys={}", vehicleId, partialPreferences.keySet());

        var preference = preferencesService.mergePreferences(
                vehicleId, partialPreferences, request.serviceIntervalMonths(), request.updatedByUserId());
        return ResponseEntity.ok(preference);
    }

    @Operation(operationId = "deleteVehiclePreferences", summary = "Delete vehicle care preferences", description = """
                    Removes the entire care-preference document for a vehicle when one exists.
                    Use this tool to clear all stored preferences at once; do not use mergeVehiclePreferences to \
                    blank individual keys when the intent is a full reset.
                    Preconditions: none; the operation is idempotent and a vehicle without preferences is left \
                    untouched.
                    Required inputs: vehicleId (UUID) as a path parameter; there is no request body.
                    Emits a VEHICLE_PREFERENCES_DELETE event, and queues a vehicle.care-preference.updated \
                    tombstone fact for CRM consumers only when a document was actually removed.
                    Returns 204 in all cases, including when no preference document exists, because deletion is \
                    idempotent.
                    """)
    @ApiResponse(
            responseCode = "204",
            description = "Preferences deleted, or no preference document existed (idempotent delete)")
    @PreAuthorize("hasAuthority('" + VehicleInventoryPermissions.PREFERENCES_MANAGE + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {VehicleInventoryPermissions.PREFERENCES_MANAGE})
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
    @Schema(description = "Request to create or fully replace vehicle care preferences")
    public record PreferencesUpsertDto(
            @NotNull
            @Schema(
                    description = "Complete preference map to store for the vehicle",
                    example = "{\"preferredOil\":\"5W-30\",\"tireRotationInterval\":5000}",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            Map<String, Object> preferences,

            @Min(1)
            @Max(120)
            @Schema(
                    description =
                            "Structured service interval in whole months; null clears the per-vehicle override so CRM falls back to its default (#1175)",
                    example = "6",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Integer serviceIntervalMonths,

            @Schema(
                    description = "Optional free-text service notes",
                    example = "Customer prefers synthetic oil only",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            String serviceNotes,

            @Schema(
                    description = "Identifier of the user creating the preferences",
                    example = "01960003-0000-7000-8000-000000000010",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            UUID createdByUserId,

            @Schema(
                    description = "Identifier of the user updating the preferences",
                    example = "01960003-0000-7000-8000-000000000011",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            UUID updatedByUserId) {}

    /**
     * DTO for merging preferences.
     */
    @Schema(description = "Request to partially merge fields into existing vehicle care preferences")
    public record PreferencesMergeDto(
            @Schema(
                    description = "Subset of preference fields to merge into existing preferences",
                    example = "{\"tireRotationInterval\":7500}",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Map<String, Object> partialPreferences,

            @Min(1)
            @Max(120)
            @Schema(
                    description =
                            "Structured service interval in whole months; null leaves the current value unchanged (#1175)",
                    example = "6",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            Integer serviceIntervalMonths,

            @Schema(
                    description = "Identifier of the user updating the preferences",
                    example = "01960003-0000-7000-8000-000000000011",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            UUID updatedByUserId) {}
}
