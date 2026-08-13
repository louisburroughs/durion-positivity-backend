package com.positivity.vehiclefitment.internal.controller;

import com.positivity.vehiclefitment.internal.dto.MakeResponse;
import com.positivity.vehiclefitment.internal.dto.ManufacturerResponse;
import com.positivity.vehiclefitment.internal.dto.ModelResponse;
import com.positivity.vehiclefitment.internal.dto.VehicleFitmentMapper;
import com.positivity.vehiclefitment.internal.dto.VehicleTypeResponse;
import com.positivity.vehiclefitment.service.VehicleFitmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "Vehicle Fitment API", description = "Endpoints for vehicle manufacturers, makes, models, and types")
@RequiredArgsConstructor
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@PreAuthorize("isAuthenticated()")
@RequestMapping("/v1/vehicle-fitment")
public class VehicleFitmentController {

    private final VehicleFitmentService vehicleFitmentService;

    @Operation(operationId = "listManufacturers", summary = "List Vehicle Manufacturers", description = """
                    Returns the list of vehicle manufacturers known to the fitment service, served from a local \
                    cache of the NHTSA vPIC registry.
                    Use this tool to start the manufacturer-make-model selection chain; do not use \
                    listMakesByManufacturer, which requires a manufacturerId taken from this response.
                    Preconditions: none beyond authentication; when the local cache is empty or stale the list is \
                    re-fetched from the public NHTSA vPIC service, so outbound connectivity is required for a refresh.
                    Required inputs: none; there are no parameters, no paging and no filtering.
                    No events are emitted; a refresh rewrites the local manufacturer cache rows, but the response is \
                    otherwise a read-only projection.
                    Returns 200 with the manufacturer list, which may be empty, and 500 when the NHTSA refresh \
                    cannot be fetched or parsed.
                    """)
    @ApiResponse(responseCode = "200", description = "List of manufacturers returned successfully.")
    @GetMapping("/manufacturers")
    public List<ManufacturerResponse> getManufacturers() {
        return vehicleFitmentService.getManufacturers().stream()
                .map(VehicleFitmentMapper::toManufacturerResponse)
                .toList();
    }

    @Operation(operationId = "listMakesByManufacturer", summary = "List Makes for a Manufacturer", description = """
                    Returns all vehicle makes recorded for one manufacturer, served from a local cache of the NHTSA \
                    vPIC registry.
                    Use this tool after picking a manufacturer from listManufacturers; do not use listModelsByMake, \
                    which descends one level further and needs a makeId taken from this response.
                    Preconditions: the manufacturer must already exist in the local cache under the supplied id; ids \
                    are the UUIDs returned by listManufacturers, not raw NHTSA numeric ids.
                    Required inputs: manufacturerId (UUID) as a path parameter.
                    No events are emitted; a cache refresh may rewrite the local make rows for the manufacturer, but \
                    nothing else changes.
                    Returns 200 with the make list, and 500 when the manufacturer id cannot be resolved or the NHTSA \
                    refresh fails, because the not-found case is not currently mapped to 404.
                    """)
    @ApiResponse(responseCode = "200", description = "List of makes returned successfully.")
    @GetMapping("/makes/{manufacturerId}")
    public List<MakeResponse> getMakesByManufacturer(
            @Parameter(description = "ID of the manufacturer", example = "00e0c0f0-0000-0000-0000-000000000000")
                    @PathVariable
                    UUID manufacturerId) {
        return vehicleFitmentService.getMakesByManufacturer(manufacturerId).stream()
                .map(VehicleFitmentMapper::toMakeResponse)
                .toList();
    }

    @Operation(operationId = "listModelsByMake", summary = "List Models for a Make", description = """
                    Returns all vehicle models recorded for one make, served from a local cache of the NHTSA vPIC \
                    registry.
                    Use this tool after picking a make from listMakesByManufacturer; do not use \
                    listVehicleTypesByMake, which returns body classes such as Passenger Car rather than named models.
                    Preconditions: the make must already exist in the local cache under the supplied id.
                    Required inputs: makeId (UUID) as a path parameter.
                    No events are emitted; a cache refresh may rewrite the local model rows for the make, but nothing \
                    else changes.
                    Returns 200 with the model list, and 500 when the make id cannot be resolved or the NHTSA \
                    refresh fails, because the not-found case is not currently mapped to 404.
                    """)
    @ApiResponse(responseCode = "200", description = "List of models returned successfully.")
    @GetMapping("/models/{makeId}")
    public List<ModelResponse> getModelsByMake(
            @Parameter(description = "ID of the make", example = "00e0c0f0-0000-0000-0000-000000000000") @PathVariable
                    UUID makeId) {
        return vehicleFitmentService.getModelsByMake(makeId).stream()
                .map(VehicleFitmentMapper::toModelResponse)
                .toList();
    }

    @Operation(operationId = "listVehicleTypesByMake", summary = "List Vehicle Types for a Make", description = """
                    Returns the vehicle types, meaning body classes such as Passenger Car or Truck, recorded for one \
                    make, served from a local cache of the NHTSA vPIC registry.
                    Use this tool when a make's body classes are needed; do not use listModelsByMake, which returns \
                    the make's named models instead.
                    Preconditions: the make must already exist in the local cache under the supplied id.
                    Required inputs: makeId (UUID) as a path parameter.
                    No events are emitted; a cache refresh may rewrite the local vehicle-type rows for the make, but \
                    nothing else changes.
                    Returns 200 with the vehicle-type list, and 500 when the make id cannot be resolved or the NHTSA \
                    refresh fails, because the not-found case is not currently mapped to 404.
                    """)
    @ApiResponse(responseCode = "200", description = "List of vehicle types returned successfully.")
    @GetMapping("/vehicle-types/{makeId}")
    public List<VehicleTypeResponse> getVehicleTypesForMake(
            @Parameter(description = "ID of the make", example = "00e0c0f0-0000-0000-0000-000000000000") @PathVariable
                    UUID makeId) {
        return vehicleFitmentService.getVehicleTypesForMake(makeId).stream()
                .map(VehicleFitmentMapper::toVehicleTypeResponse)
                .toList();
    }
}
