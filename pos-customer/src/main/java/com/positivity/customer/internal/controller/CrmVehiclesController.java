package com.positivity.customer.internal.controller;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
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

import com.positivity.customer.internal.dto.CreateVehicleForPartyRequest;
import com.positivity.customer.internal.dto.VehicleTransferRequest;
import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.service.CrmVehicleService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.dto.VehicleResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Controller implementing CRM vehicle endpoints from the API catalog.
 * Delegates vehicle CRUD to pos-vehicle-inventory and manages customer-vehicle
 * associations.
 */
@Tag(name = "CRM Vehicles", description = "Vehicle management operations for customers")
@RestController
@RequestMapping("/v1/crm")
@RequiredArgsConstructor
public class CrmVehiclesController {

        private static final Logger log = LoggerFactory.getLogger(CrmVehiclesController.class);

        private final CrmVehicleService crmVehicleService;

        @Operation(summary = "Create vehicle", description = "Create a new vehicle for a customer")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "201", description = "Vehicle created successfully", content = @Content(schema = @Schema(implementation = VehicleResponse.class))),
                        @ApiResponse(responseCode = "400", description = "Bad request - invalid input", content = @Content),
                        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content),
                        @ApiResponse(responseCode = "404", description = "Customer not found", content = @Content)
        })
        @PostMapping("/{customerId}/vehicles")
        @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.VEHICLE_CREATE + "')")
        @EmitEvent(id = "CUSTOMER_VEHICLE_CREATE_LEGACY", apiVersion = "1")
        public ResponseEntity<VehicleResponse> createVehicles(
                        @Parameter(description = "Customer ID", required = true) @PathVariable UUID customerId,
                        @Parameter(description = "Vehicle creation request", required = true) @RequestBody CreateVehicleForPartyRequest body) {
                log.info("Creating vehicle for customer: {}", customerId);
                VehicleResponse response = crmVehicleService.createVehicle(customerId, body);
                return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }

        @Operation(summary = "Update vehicle", description = "Update vehicle information for a customer")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "200", description = "Vehicle updated successfully", content = @Content(schema = @Schema(implementation = VehicleResponse.class))),
                        @ApiResponse(responseCode = "400", description = "Bad request - invalid input", content = @Content),
                        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content),
                        @ApiResponse(responseCode = "404", description = "Customer or vehicle not found", content = @Content)
        })
        @PutMapping("/{customerId}/vehicles/{vehicleId}")
        @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.VEHICLE_EDIT + "')")
        @EmitEvent(id = "CUSTOMER_VEHICLE_UPDATE", apiVersion = "1")
        public ResponseEntity<VehicleResponse> updateVehicles(
                        @Parameter(description = "Customer ID", required = true) @PathVariable UUID customerId,
                        @Parameter(description = "Vehicle ID", required = true) @PathVariable UUID vehicleId,
                        @Parameter(description = "Vehicle update request", required = true) @RequestBody CreateVehicleForPartyRequest body) {
                log.info("Updating vehicle {} for customer: {}", vehicleId, customerId);
                VehicleResponse response = crmVehicleService.updateVehicle(customerId, body, vehicleId);
                return ResponseEntity.ok(response);
        }

        @Operation(summary = "Delete vehicle", description = "Delete or deactivate a vehicle for a customer")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "204", description = "Vehicle deleted successfully", content = @Content),
                        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content),
                        @ApiResponse(responseCode = "404", description = "Customer or vehicle not found", content = @Content)
        })
        @DeleteMapping("/{customerId}/vehicles/{vehicleId}")
        @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.VEHICLE_DEACTIVATE + "')")
        @EmitEvent(id = "CUSTOMER_VEHICLE_DELETE", apiVersion = "1")
        public ResponseEntity<Void> deleteVehicle(
                        @Parameter(description = "Customer ID", required = true) @PathVariable UUID customerId,
                        @Parameter(description = "Vehicle ID", required = true) @PathVariable UUID vehicleId) {
                log.info("Deleting vehicle {} for customer: {}", vehicleId, customerId);
                crmVehicleService.deleteVehicle(customerId, vehicleId);
                return ResponseEntity.noContent().build();
        }

        @Operation(summary = "Transfer vehicle", description = "Transfer vehicle ownership between customers")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "200", description = "Vehicle transferred successfully", content = @Content(schema = @Schema(implementation = VehicleResponse.class))),
                        @ApiResponse(responseCode = "400", description = "Bad request - invalid input", content = @Content),
                        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content),
                        @ApiResponse(responseCode = "404", description = "Customer or vehicle not found", content = @Content)
        })
        @PutMapping("/{customerId}/vehicles/{vehicleId}/transfer")
        @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.VEHICLE_PARTY_ASSOC_EDIT + "')")
        @EmitEvent(id = "CUSTOMER_VEHICLE_TRANSFER", apiVersion = "1")
        public ResponseEntity<VehicleResponse> transferVehicles(
                        @Parameter(description = "Source customer ID", required = true) @PathVariable UUID customerId,
                        @Parameter(description = "Vehicle ID to transfer", required = true) @PathVariable UUID vehicleId,
                        @Parameter(description = "Transfer request with target customer", required = true) @RequestBody VehicleTransferRequest body) {
                VehicleResponse response = crmVehicleService.transferVehicle(customerId, vehicleId, body);
                return ResponseEntity.ok(response);
        }

        @Operation(summary = "Get vehicle for customer", description = "Retrieve a specific vehicle for a given customer")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "200", description = "Vehicle found", content = @Content(schema = @Schema(implementation = VehicleResponse.class))),
                        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content),
                        @ApiResponse(responseCode = "404", description = "Customer or vehicle not found", content = @Content)
        })
        @GetMapping("/{customerId}/vehicles/{vehicleId}")
        @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.VEHICLE_VIEW + "')")
        public ResponseEntity<VehicleResponse> getVehiclesForCustomer(
                        @Parameter(description = "Customer ID", required = true) @PathVariable UUID customerId,
                        @Parameter(description = "Vehicle ID", required = true) @PathVariable UUID vehicleId) {
                log.info("Fetching vehicle {} for customer: {}", vehicleId, customerId);
                return crmVehicleService.getVehicleForCustomer(customerId, vehicleId)
                                .map(ResponseEntity::ok)
                                .orElse(ResponseEntity.notFound().build());
        }
}
