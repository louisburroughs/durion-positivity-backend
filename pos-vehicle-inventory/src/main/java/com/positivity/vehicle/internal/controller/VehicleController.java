package com.positivity.vehicle.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.vehicle.internal.dto.VehicleLegacyRequest;
import com.positivity.vehicle.internal.dto.VehicleLegacyResponse;
import com.positivity.vehicle.internal.service.VehicleLegacyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Legacy vehicle CRUD surface.
 *
 * <p>
 * Domain rejections propagate as {@code VehicleValidationException}, which
 * {@code VehicleExceptionHandler} maps to 400 with the {@code ApiError} envelope
 * (ADR-0017). Absence is still reported here, because these service methods
 * signal it with an empty {@link java.util.Optional} or a {@code false} return
 * rather than an exception.
 */
@Tag(name = "Vehicle API", description = "Endpoints for vehicle CRUD and VIN-based operations")
@RequiredArgsConstructor
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@PreAuthorize("isAuthenticated()")
@RequestMapping("/v1/vehicles-legacy")
public class VehicleController {

    private static final String LEGACY_VEHICLE_EXAMPLE = """
            {"make":"Honda",
             "model":"Accord",
             "year":2003,
             "vin":"1HGCM82633A004352",
             "vehicleType":"CAR"}
            """;

    private final VehicleLegacyService vehicleLegacyService;

    @Operation(operationId = "createVehicleLegacy", summary = "Create a new vehicle", description = """
                    Creates a vehicle in the legacy vehicle store, persisting make, model, year and an optional VIN \
                    without enforcing VIN uniqueness.
                    Use this tool only when maintaining the legacy CRUD surface under /v1/vehicles-legacy; do not use \
                    it for registry vehicles, which createVehicle owns and which replicate to consumer services.
                    Preconditions: none beyond an authenticated caller; duplicate VINs are accepted because the \
                    legacy store has no uniqueness check.
                    Required inputs: make, model and year (year between 1886 and the current year plus one); vin is \
                    optional on this operation but must not be blank when present, and vehicleType is optional and \
                    limited to CAR, VAN, COMMERCIAL_TRUCK, PASSENGER_TRUCK or TRUCK.
                    Emits a VEHICLE_CREATE event; no replica fact is published, because the legacy store does not \
                    feed the vehicle.events.v1 stream.
                    Returns 200 with the stored vehicle, and 400 with a VALIDATION_ERROR ApiError when make, model \
                    or year is missing, year is out of range, vin is blank or vehicleType is unsupported.
                    """)
    @ApiResponse(responseCode = "200", description = "Vehicle created successfully.")
    @EmitEvent(id = "VEHICLE_CREATE", apiVersion = "1")
    @PostMapping
    public ResponseEntity<VehicleLegacyResponse> createVehicle(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Legacy vehicle to store, identified by its core make, model, year"
                                    + " and optional VIN fields.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Legacy vehicle",
                                                            value = LEGACY_VEHICLE_EXAMPLE)))
                    @RequestBody
                    VehicleLegacyRequest vehicle) {
        return ResponseEntity.ok(vehicleLegacyService.createVehicle(vehicle));
    }

    @Operation(operationId = "getVehicleLegacy", summary = "Get vehicle by ID", description = """
                    Returns a single vehicle from the legacy vehicle store by its UUID primary key.
                    Use this tool when the legacy vehicle id is already known; use getVehicleLegacyByVin instead \
                    when only the VIN is known, and do not use getVehicle, which reads the separate registry store.
                    Preconditions: the vehicle must exist in the legacy store; legacy deletes are hard deletes, so \
                    a deleted vehicle is gone rather than flagged inactive.
                    Required inputs: id (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 with an empty body when no legacy vehicle exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "Vehicle found and returned.")
    @ApiResponse(
            responseCode = "404",
            description = "Vehicle not found.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/{id}")
    public ResponseEntity<VehicleLegacyResponse> getVehicle(
            @Parameter(description = "ID of the vehicle to retrieve", example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b")
                    @PathVariable
                    UUID id) {
        return vehicleLegacyService
                .getVehicle(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(operationId = "listVehiclesLegacy", summary = "Get all vehicles", description = """
                    Returns every vehicle in the legacy vehicle store as a flat list with no paging or filtering.
                    Use this tool to enumerate the legacy store; do not use searchVehicles, which queries the \
                    registry store, and prefer getVehicleLegacy when a specific id is already known.
                    Preconditions: none; an empty store simply yields an empty list.
                    Required inputs: none; there are no parameters and no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 in all cases, including an empty JSON array when the store holds no vehicles.
                    """)
    @ApiResponse(responseCode = "200", description = "List of vehicles returned successfully.")
    @GetMapping
    public List<VehicleLegacyResponse> getAllVehicles() {
        return vehicleLegacyService.getAllVehicles();
    }

    @Operation(operationId = "updateVehicleLegacy", summary = "Update vehicle by ID", description = """
                    Replaces the core fields of an existing legacy vehicle identified by its UUID, applying make, \
                    model, year, VIN and vehicleType from the request.
                    Use this tool to correct a legacy record by id; use updateVehicleLegacyByVin instead when only \
                    the VIN is known, and do not use updateVehicle, which patches registry vehicles field by field.
                    Preconditions: the vehicle must already exist in the legacy store; the request is a full \
                    replacement of core fields rather than a partial patch.
                    Required inputs: id (UUID) as a path parameter plus make, model and year in the body (year \
                    between 1886 and the current year plus one); vin and vehicleType are optional.
                    Emits a VEHICLE_UPDATE event; no replica fact is published from the legacy surface.
                    Returns 404 with an empty body when the id is unknown, and 400 with a VALIDATION_ERROR ApiError \
                    when make, model or year is missing or invalid.
                    """)
    @ApiResponse(responseCode = "200", description = "Vehicle updated successfully.")
    @ApiResponse(
            responseCode = "404",
            description = "Vehicle not found.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "VEHICLE_UPDATE", apiVersion = "1")
    @PutMapping("/{id}")
    public ResponseEntity<VehicleLegacyResponse> updateVehicle(
            @Parameter(description = "ID of the vehicle to update", example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b")
                    @PathVariable
                    UUID id,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Full replacement values for the legacy vehicle's core fields.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Legacy vehicle",
                                                            value = LEGACY_VEHICLE_EXAMPLE)))
                    @RequestBody
                    VehicleLegacyRequest updated) {
        // ResponseEntity.of: 200 + body when present, 404 when empty (Sonar S6863).
        return ResponseEntity.of(vehicleLegacyService.updateVehicle(id, updated));
    }

    @Operation(operationId = "deleteVehicleLegacy", summary = "Delete vehicle by ID", description = """
                    Permanently deletes a vehicle from the legacy vehicle store; the row is removed rather than \
                    deactivated.
                    Use this tool to purge a legacy record by id; do not use deleteVehicle, which soft-deactivates \
                    a registry vehicle and keeps it readable.
                    Preconditions: the vehicle must exist in the legacy store; deletion is not recoverable through \
                    this API.
                    Required inputs: id (UUID) as a path parameter; there is no request body.
                    Emits a VEHICLE_DELETE event; no replica fact is published from the legacy surface.
                    Returns 204 on successful deletion, and 404 with an empty body when the id is unknown.
                    """)
    @ApiResponse(responseCode = "204", description = "Vehicle deleted successfully.")
    @ApiResponse(
            responseCode = "404",
            description = "Vehicle not found.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "VEHICLE_DELETE", apiVersion = "1")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteVehicle(
            @Parameter(description = "ID of the vehicle to delete", example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b")
                    @PathVariable
                    UUID id) {
        if (!vehicleLegacyService.deleteVehicle(id)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "createVehicleLegacyByVin", summary = "Create vehicle by VIN", description = """
                    Creates a vehicle in the legacy vehicle store with the VIN treated as mandatory, trimming the \
                    supplied value before storing it.
                    Use this tool when the VIN is the identifying field for a legacy record; do not use \
                    createVehicleLegacy, which accepts a missing VIN, and do not use createVehicle, which writes \
                    the registry store.
                    Preconditions: none beyond an authenticated caller; the VIN is not checked for uniqueness or \
                    17-character format by this legacy surface.
                    Required inputs: make, model, year and vin in the body (year between 1886 and the current year \
                    plus one); vehicleType is optional and limited to CAR, VAN, COMMERCIAL_TRUCK, PASSENGER_TRUCK \
                    or TRUCK.
                    Emits a VEHICLE_CREATE event; no replica fact is published from the legacy surface.
                    Returns 200 with the stored vehicle, and 400 with a VALIDATION_ERROR ApiError when make, model, \
                    year or vin is missing or invalid.
                    """)
    @ApiResponse(responseCode = "200", description = "Vehicle created successfully.")
    @EmitEvent(id = "VEHICLE_CREATE", apiVersion = "1")
    @PostMapping("/vin")
    public ResponseEntity<VehicleLegacyResponse> createVehicleByVIN(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Legacy vehicle to store, with the VIN required as its identifying field.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Legacy vehicle",
                                                            value = LEGACY_VEHICLE_EXAMPLE)))
                    @RequestBody
                    VehicleLegacyRequest vehicle) {
        return ResponseEntity.ok(vehicleLegacyService.createVehicleByVin(vehicle));
    }

    @Operation(operationId = "getVehicleLegacyByVin", summary = "Get vehicle by VIN", description = """
                    Returns a single vehicle from the legacy vehicle store by VIN, trimming the supplied value \
                    before the lookup.
                    Use this tool when only the VIN of a legacy record is known; use getVehicleLegacy instead when \
                    the id is known, and do not use getVehicleByVin, which reads the registry store with normalized \
                    VINs.
                    Preconditions: the vehicle must exist in the legacy store; the lookup is an exact match on the \
                    trimmed VIN, not a normalized or partial match.
                    Required inputs: vin as a non-blank path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 with an empty body when no legacy vehicle carries the VIN, and 400 with a \
                    VALIDATION_ERROR ApiError when the VIN is blank.
                    """)
    @ApiResponse(responseCode = "200", description = "Vehicle found and returned.")
    @ApiResponse(
            responseCode = "404",
            description = "Vehicle not found.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/vin/{vin}")
    public ResponseEntity<VehicleLegacyResponse> getVehicleByVIN(
            @Parameter(description = "VIN of the vehicle to retrieve", example = "1HGCM82633A004352") @PathVariable
                    String vin) {
        // ResponseEntity.of: 200 + body when present, 404 when empty (Sonar S6863).
        return ResponseEntity.of(vehicleLegacyService.getVehicleByVin(vin));
    }

    @Operation(operationId = "updateVehicleLegacyByVin", summary = "Update vehicle by VIN", description = """
                    Replaces the core fields of an existing legacy vehicle located by VIN, applying make, model, \
                    year and vehicleType from the request.
                    Use this tool to correct a legacy record when only the VIN is known; use updateVehicleLegacy \
                    instead when the id is known, and do not use updateVehicle, which patches registry vehicles.
                    Preconditions: a vehicle with the trimmed VIN must already exist in the legacy store; the \
                    request fully replaces core fields rather than patching them.
                    Required inputs: vin as a path parameter plus make, model and year in the body (year between \
                    1886 and the current year plus one); vehicleType is optional.
                    Emits a VEHICLE_UPDATE event; no replica fact is published from the legacy surface.
                    Returns 404 with an empty body when no legacy vehicle carries the VIN, and 400 with a \
                    VALIDATION_ERROR ApiError when a required body field is missing or invalid.
                    """)
    @ApiResponse(responseCode = "200", description = "Vehicle updated successfully.")
    @ApiResponse(
            responseCode = "404",
            description = "Vehicle not found.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "VEHICLE_UPDATE", apiVersion = "1")
    @PutMapping("/vin/{vin}")
    public ResponseEntity<VehicleLegacyResponse> updateVehicleByVIN(
            @Parameter(description = "VIN of the vehicle to update", example = "1HGCM82633A004352") @PathVariable
                    String vin,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Full replacement values for the legacy vehicle's core fields.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Legacy vehicle",
                                                            value = LEGACY_VEHICLE_EXAMPLE)))
                    @RequestBody
                    VehicleLegacyRequest updated) {
        // ResponseEntity.of: 200 + body when present, 404 when empty (Sonar S6863).
        return ResponseEntity.of(vehicleLegacyService.updateVehicleByVin(vin, updated));
    }

    @Operation(operationId = "deleteVehicleLegacyByVin", summary = "Delete vehicle by VIN", description = """
                    Permanently deletes a vehicle from the legacy vehicle store located by VIN; the row is removed \
                    rather than deactivated.
                    Use this tool to purge a legacy record when only the VIN is known; use deleteVehicleLegacy \
                    instead when the id is known, and do not use deleteVehicle, which soft-deactivates registry \
                    vehicles.
                    Preconditions: a vehicle with the trimmed VIN must exist in the legacy store; deletion is not \
                    recoverable through this API.
                    Required inputs: vin as a non-blank path parameter; there is no request body.
                    Emits a VEHICLE_DELETE event; no replica fact is published from the legacy surface.
                    Returns 204 on successful deletion, and 404 with an empty body when no legacy vehicle carries \
                    the VIN.
                    """)
    @ApiResponse(responseCode = "204", description = "Vehicle deleted successfully.")
    @ApiResponse(
            responseCode = "404",
            description = "Vehicle not found.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @EmitEvent(id = "VEHICLE_DELETE", apiVersion = "1")
    @DeleteMapping("/vin/{vin}")
    public ResponseEntity<Void> deleteVehicleByVIN(
            @Parameter(description = "VIN of the vehicle to delete", example = "1HGCM82633A004352") @PathVariable
                    String vin) {
        if (!vehicleLegacyService.deleteVehicleByVin(vin)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }
}
