package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Stub controller implementing CRM vehicle endpoints from the API catalog.
 *
 * Intentionally unimplemented: returns 501 for all operations.
 */
@Tag(name = "CRM Vehicles", description = "Vehicle management operations for customers (stub endpoints)")
@RestController
@RequestMapping("/v1/crm")
public class CrmVehiclesController {

        private static final Logger log = LoggerFactory.getLogger(CrmVehiclesController.class);

        @Operation(summary = "Create vehicle", description = "Create a new vehicle for a customer (stub endpoint)")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "501", description = "Not implemented", content = @Content),
                        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
        })
        @PostMapping("/{customerId}/vehicles")
        @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.VEHICLE_CREATE + "')")
        @EmitEvent(id = "CUSTOMER_VEHICLE_CREATE_LEGACY", apiVersion = "1")
        public ResponseEntity<Void> createVehicles(
                        @Parameter(description = "Customer ID", required = true) @PathVariable String customerId,
                        @Parameter(description = "Vehicle creation request", required = false) @RequestBody(required = false) Object body) {
                log.info("Stub createVehicles customerId={}", customerId);
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }

        @Operation(summary = "Update vehicle", description = "Update vehicle information for a customer (stub endpoint)")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "501", description = "Not implemented", content = @Content),
                        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
        })
        @PutMapping("/{customerId}/vehicles")
        @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.VEHICLE_EDIT + "')")
        @EmitEvent(id = "CUSTOMER_VEHICLE_UPDATE", apiVersion = "1")
        public ResponseEntity<Void> updateVehicles(
                        @Parameter(description = "Customer ID", required = true) @PathVariable String customerId,
                        @Parameter(description = "Vehicle update request", required = false) @RequestBody(required = false) Object body) {
                log.info("Stub updateVehicles customerId={}", customerId);
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }

        @Operation(summary = "Delete vehicle", description = "Delete or deactivate a vehicle for a customer (stub endpoint)")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "501", description = "Not implemented", content = @Content),
                        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
        })
        @DeleteMapping("/{customerId}/vehicles/{vehicleId}")
        @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.VEHICLE_DEACTIVATE + "')")
        public ResponseEntity<Void> deleteVehicle(
                        @Parameter(description = "Customer ID", required = true) @PathVariable String customerId,
                        @Parameter(description = "Vehicle ID", required = true) @PathVariable String vehicleId) {
                log.info("Stub deleteVehicle customerId={} vehicleId={}", customerId, vehicleId);
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }

        @Operation(summary = "Transfer vehicle", description = "Transfer vehicle ownership between customers (stub endpoint)")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "501", description = "Not implemented", content = @Content),
                        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
        })
        @PutMapping("/{customerId}/vehicles/{vehicleId}/transfer")
        @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.VEHICLE_PARTY_ASSOC_EDIT + "')")
        @EmitEvent(id = "CUSTOMER_VEHICLE_TRANSFER", apiVersion = "1")
        public ResponseEntity<Void> transferVehicles(
                        @Parameter(description = "Source customer ID", required = true) @PathVariable String customerId,
                        @Parameter(description = "Vehicle ID to transfer", required = true) @PathVariable String vehicleId,
                        @Parameter(description = "Transfer request with target customer", required = false) @RequestBody(required = false) Object body) {
                log.info("Stub transferVehicles customerId={} vehicleId={}", customerId, vehicleId);
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }

        @Operation(summary = "Get vehicle by ID", description = "Retrieve vehicle details by vehicle ID (stub endpoint)")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "501", description = "Not implemented", content = @Content),
                        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
        })
        @GetMapping("/vehicles/{vehicleId}")
        @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.VEHICLE_VIEW + "')")
        public ResponseEntity<Void> getVehicle(
                        @Parameter(description = "Vehicle ID", required = true) @PathVariable String vehicleId) {
                log.info("Stub getVehicle vehicleId={}", vehicleId);
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }

        @Operation(summary = "Search vehicles", description = "Search and filter vehicles across all customers (stub endpoint)")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "501", description = "Not implemented", content = @Content),
                        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
        })
        @GetMapping("/vehicles")
        @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.VEHICLE_SEARCH + "')")
        public ResponseEntity<Void> getAllVehiclesFiltered() {
                log.info("Stub getAllVehiclesFiltered");
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }

        @Operation(summary = "Get vehicle for customer", description = "Retrieve a specific vehicle for a given customer (stub endpoint)")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "501", description = "Not implemented", content = @Content),
                        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
        })
        @GetMapping("/{customerId}/vehicles/{vehicleId}")
        @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.VEHICLE_VIEW + "')")
        public ResponseEntity<Void> getVehiclesForCustomer(
                        @Parameter(description = "Customer ID", required = true) @PathVariable String customerId,
                        @Parameter(description = "Vehicle ID", required = true) @PathVariable String vehicleId) {
                log.info("Stub getVehiclesForCustomer customerId={} vehicleId={}", customerId, vehicleId);
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
        }
}
