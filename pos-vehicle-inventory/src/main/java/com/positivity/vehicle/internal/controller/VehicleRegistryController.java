package com.positivity.vehicle.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.dto.CreateVehicleRequest;
import com.positivity.shared.dto.UpdateVehicleRequest;
import com.positivity.shared.dto.VehicleResponse;
import com.positivity.vehicle.service.VehicleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for vehicle registry CRUD operations.
 *
 * <p>
 * Failures are left to {@code VehicleExceptionHandler}, which maps
 * {@code EntityNotFoundException} to 404 and {@code IllegalArgumentException} to
 * 400 and returns the {@code ApiError} envelope (ADR-0017) in both cases.
 */
@Tag(name = "Vehicle Registry API", description = "Operations for creating and maintaining vehicle registry records")
@RequiredArgsConstructor
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@Validated
@PreAuthorize("isAuthenticated()")
@RequestMapping("/v1/vehicle-registry")
public class VehicleRegistryController {

    private static final String CREATE_VEHICLE_EXAMPLE = """
            {"accountId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
             "vin":"1HGCM82633A004352",
             "unitNumber":"UNIT-1042",
             "description":"2003 Honda Accord EX Sedan",
             "licensePlate":"ABC1234",
             "licensePlateJurisdiction":"CA",
             "year":2003,
             "make":"Honda",
             "model":"Accord",
             "trim":"EX"}
            """;

    private static final String UPDATE_VEHICLE_EXAMPLE = """
            {"description":"2003 Honda Accord EX Sedan, repainted",
             "licensePlate":"XYZ7890",
             "licensePlateJurisdiction":"TX"}
            """;

    private final VehicleService vehicleService;

    @Operation(operationId = "createVehicle", summary = "Create vehicle", description = """
                    Creates an active vehicle registry record after validating and normalizing the VIN, which must \
                    be globally unique among active vehicles.
                    Use this tool to register a single vehicle whose changes replicate to consumer services; do not \
                    use bulkIngestVehicles, which loads batches with per-row results, and do not use \
                    createVehicleLegacy, which writes the unreplicated legacy store.
                    Preconditions: no active vehicle may already hold the same normalized VIN; a deactivated \
                    vehicle releases its VIN for reuse.
                    Required inputs: accountId (UUID) and vin (17 characters after separators are stripped, with \
                    letters I, O and Q rejected); unitNumber and description are optional and stored as empty \
                    strings when omitted, and licensePlate, licensePlateJurisdiction, year, make, model and trim \
                    are optional.
                    Emits a VEHICLE_CREATE event and queues a vehicle.vehicle.updated fact on the vehicle.events.v1 \
                    outbox for downstream replicas.
                    Returns 201 with the created record, and 400 with a VALIDATION_ERROR ApiError when the VIN is \
                    malformed or an active vehicle already holds the same normalized VIN; the duplicate-VIN case is \
                    reported as 400, not 409.
                    """)
    @ApiResponse(responseCode = "201", description = "Vehicle created successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid vehicle request.")
    @EmitEvent(id = "VEHICLE_CREATE", apiVersion = "1")
    @PostMapping
    public ResponseEntity<VehicleResponse> createVehicle(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Registry vehicle to create, owned by one account and identified by a"
                                    + " globally unique VIN.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Registry vehicle",
                                                            value = CREATE_VEHICLE_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    CreateVehicleRequest request) {
        VehicleResponse response = vehicleService.createVehicle(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(operationId = "getVehicle", summary = "Get vehicle by ID", description = """
                    Returns a single vehicle registry record by its vehicleId, whether the vehicle is active or \
                    deactivated.
                    Use this tool when the registry id is already known; use getVehicleByVin instead for VIN \
                    lookups, use searchVehicles for free-text discovery, and do not use getVehicleLegacy, which \
                    reads the legacy store.
                    Preconditions: the record must exist in the registry; deactivated vehicles are still returned, \
                    so callers must check the isActive flag.
                    Required inputs: vehicleId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 with an empty body when no registry record exists for the supplied vehicleId.
                    """)
    @ApiResponse(responseCode = "200", description = "Vehicle retrieved successfully.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @GetMapping("/{vehicleId}")
    public ResponseEntity<VehicleResponse> getVehicle(
            @Parameter(description = "Vehicle UUID", required = true) @PathVariable @NotNull UUID vehicleId) {
        return vehicleService
                .getVehicle(vehicleId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(operationId = "getVehicleByVin", summary = "Get vehicle by VIN", description = """
                    Returns a single vehicle registry record by VIN, normalizing the supplied value by trimming, \
                    uppercasing and stripping separators before the lookup.
                    Use this tool when the full 17-character VIN is known; use searchVehicles instead for partial \
                    VIN prefixes, and do not use getVehicleLegacyByVin, which reads the legacy store.
                    Preconditions: a record with the normalized VIN must exist in the registry; active and \
                    deactivated vehicles are both returned.
                    Required inputs: vin as a path parameter, exactly 17 characters; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 with an empty body when no registry record carries the normalized VIN, and 400 \
                    with a VALIDATION_FAILED ApiError when the path VIN is not exactly 17 characters.
                    """)
    @ApiResponse(responseCode = "200", description = "Vehicle retrieved successfully.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @GetMapping("/vin/{vin}")
    public ResponseEntity<VehicleResponse> getVehicleByVin(
            @Parameter(description = "Vehicle VIN", required = true) @PathVariable @NotBlank @Size(min = 17, max = 17)
                    String vin) {
        return vehicleService
                .getVehicleByVin(vin)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(operationId = "updateVehicle", summary = "Update vehicle", description = """
                    Applies a partial update to a vehicle registry record; only fields present in the body are \
                    changed, and the VIN itself cannot be modified here.
                    Use this tool to correct registry data or transfer ownership, since a new accountId moves the \
                    vehicle to that party and consumer replicas move their vehicle-party association from the \
                    emitted fact (ADR-0044); do not use updateVehicleLegacy, which fully replaces legacy records.
                    Preconditions: the record must exist in the registry; deactivated vehicles can still be updated.
                    Required inputs: vehicleId (UUID) as a path parameter and at least one body field among \
                    accountId, unitNumber, description, licensePlate, licensePlateJurisdiction, year, make, model \
                    and trim; a provided unitNumber or description must be non-blank, and year must be between \
                    1886 and 2100.
                    Emits a VEHICLE_UPDATE event and queues a vehicle.vehicle.updated fact on the vehicle.events.v1 \
                    outbox for downstream replicas.
                    Returns 404 with a RESOURCE_NOT_FOUND ApiError when the vehicleId is unknown, and 400 with a \
                    VALIDATION_FAILED ApiError when no field is provided or a provided field violates its \
                    constraints.
                    """)
    @ApiResponse(responseCode = "200", description = "Vehicle updated successfully.")
    @ApiResponse(responseCode = "400", description = "Invalid vehicle request.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @EmitEvent(id = "VEHICLE_UPDATE", apiVersion = "1")
    @PutMapping("/{vehicleId}")
    public ResponseEntity<VehicleResponse> updateVehicle(
            @Parameter(description = "Vehicle UUID", required = true) @PathVariable @NotNull UUID vehicleId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Subset of mutable vehicle fields to change; omitted fields keep their"
                                    + " stored values.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Partial update",
                                                            value = UPDATE_VEHICLE_EXAMPLE)))
                    @Valid
                    @NotNull
                    @RequestBody
                    UpdateVehicleRequest request) {
        VehicleResponse response = vehicleService.updateVehicle(vehicleId, request);
        return ResponseEntity.ok(response);
    }

    @Operation(operationId = "deleteVehicle", summary = "Delete vehicle", description = """
                    Deactivates a vehicle registry record by setting isActive to false; the row is retained and \
                    remains readable by id and by VIN.
                    Use this tool to retire a registry vehicle while preserving history and releasing its VIN for \
                    reuse by a future active vehicle; do not use deleteVehicleLegacy, which hard-deletes from the \
                    legacy store.
                    Preconditions: the record must exist in the registry; deactivating an already inactive vehicle \
                    is accepted and simply re-emits the replica fact.
                    Required inputs: vehicleId (UUID) as a path parameter; there is no request body.
                    Emits a VEHICLE_DELETE event and queues a vehicle.vehicle.updated fact with isActive false on \
                    the vehicle.events.v1 outbox, which tells downstream replicas to treat the vehicle as retired.
                    Returns 204 on successful deactivation, and 404 with a RESOURCE_NOT_FOUND ApiError when the \
                    vehicleId is unknown.
                    """)
    @ApiResponse(responseCode = "204", description = "Vehicle deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Vehicle not found.")
    @EmitEvent(id = "VEHICLE_DELETE", apiVersion = "1")
    @DeleteMapping("/{vehicleId}")
    public ResponseEntity<Void> deleteVehicle(
            @Parameter(description = "Vehicle UUID", required = true) @PathVariable @NotNull UUID vehicleId) {
        vehicleService.deleteVehicle(vehicleId);
        return ResponseEntity.noContent().build();
    }
}
