package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.service.CrmVehicleService;
import com.positivity.shared.dto.VehicleResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only CRM vehicle endpoints, served from the local {@code ext_vehicle} replica
 * (ADR-0044 §6, #843). Vehicle registry writes (create/update/transfer/deactivate) go directly
 * to pos-vehicle-inventory through the gateway; the customer-owned vehicle-party association
 * follows via {@code vehicle.events.v1}.
 */
@Tag(name = "CRM Vehicles", description = "Vehicle management operations for customers")
@RestController
@RequestMapping("/v1/crm")
@RequiredArgsConstructor
public class CrmVehiclesController {

    private static final Logger log = LoggerFactory.getLogger(CrmVehiclesController.class);

    private final CrmVehicleService crmVehicleService;

    @Operation(operationId = "listVehiclesForCustomer", summary = "List Customer Vehicles", description = """
                    Returns the vehicle summaries (vehicleId, VIN, make, model, year) associated with a \
                    customer, resolved from the party's VIN set against the local ext_vehicle replica of \
                    pos-vehicle-inventory.
                    Use this tool when the vehicle UUIDs for a known customer are needed, for example to \
                    build an estimate; use getVehicleForCustomer instead for one vehicle's full record, and \
                    note vehicle writes go to pos-vehicle-inventory, not this module.
                    Preconditions: the customer must exist as a person or commercial party; VINs whose \
                    vehicle events have not yet been replicated are silently dropped from the list.
                    Required inputs: customerId (UUID, the party id) as a path parameter; there is no \
                    request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no party exists for the supplied customerId, and 200 with an empty \
                    list when the customer owns no resolvable vehicles.
                    """)
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Vehicles listed (possibly empty)",
                        content =
                                @Content(
                                        array =
                                                @io.swagger.v3.oas.annotations.media.ArraySchema(
                                                        schema =
                                                                @Schema(
                                                                        implementation =
                                                                                com.positivity.customer.internal.dto
                                                                                        .snapshot.CrmSnapshotDTO
                                                                                        .VehicleSummary.class)))),
                @ApiResponse(
                        responseCode = "403",
                        description = "Forbidden - insufficient permissions",
                        content = @Content),
                @ApiResponse(responseCode = "404", description = "Customer not found", content = @Content)
            })
    @GetMapping("/{customerId}/vehicles")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.VEHICLE_VIEW})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.VEHICLE_VIEW + "')")
    public ResponseEntity<java.util.List<com.positivity.customer.internal.dto.snapshot.CrmSnapshotDTO.VehicleSummary>>
            listVehiclesForCustomer(
                    @Parameter(description = "Customer ID", required = true) @PathVariable UUID customerId) {
        log.info("Listing vehicles for customer: {}", customerId);
        return crmVehicleService
                .findVehiclesForCustomer(customerId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(operationId = "getVehicleForCustomer", summary = "Get Customer Vehicle", description = """
                    Returns one vehicle's record from the local ext_vehicle replica, verified to belong to \
                    the named customer through the party's VIN association.
                    Use this tool when both the customer and vehicle ids are known; use \
                    listVehiclesForCustomer instead to discover which vehicles a customer owns.
                    Preconditions: the customer must exist, the vehicle must exist in the replica, and the \
                    vehicle must be associated with that customer.
                    Required inputs: customerId and vehicleId (UUIDs) as path parameters; there is no \
                    request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when the customer, the vehicle, or the ownership association cannot be \
                    resolved.
                    """)
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Vehicle found",
                        content = @Content(schema = @Schema(implementation = VehicleResponse.class))),
                @ApiResponse(
                        responseCode = "403",
                        description = "Forbidden - insufficient permissions",
                        content = @Content),
                @ApiResponse(responseCode = "404", description = "Customer or vehicle not found", content = @Content)
            })
    @GetMapping("/{customerId}/vehicles/{vehicleId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.VEHICLE_VIEW})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.VEHICLE_VIEW + "')")
    public ResponseEntity<VehicleResponse> getVehiclesForCustomer(
            @Parameter(description = "Customer ID", required = true) @PathVariable UUID customerId,
            @Parameter(description = "Vehicle ID", required = true) @PathVariable UUID vehicleId) {
        log.info("Fetching vehicle {} for customer: {}", vehicleId, customerId);
        return crmVehicleService
                .getVehicleForCustomer(customerId, vehicleId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
