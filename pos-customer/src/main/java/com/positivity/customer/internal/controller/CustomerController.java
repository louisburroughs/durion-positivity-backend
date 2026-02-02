package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.dto.CustomerDTO;
import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.service.CustomerService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@Tag(name = "Customer API", description = "Operations related to customers")
@RestController
@RequestMapping("/v1/crm")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @Operation(summary = "Get all customers", description = "Retrieve a list of all customers.")
    @ApiResponse(responseCode = "200", description = "List of customers returned successfully.")
    @GetMapping
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
    public List<CustomerDTO> getAllCustomers() {
        log.info("Fetching all customers");
        return customerService.getAllCustomers();
    }

    @Operation(summary = "Get customer by ID", description = "Retrieve a customer by their unique ID.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Customer found and returned."),
            @ApiResponse(responseCode = "404", description = "Customer not found.")
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
    public ResponseEntity<CustomerDTO> getCustomerById(
            @Parameter(description = "ID of the customer to retrieve", example = "123e4567-e89b-12d3-a456-426614174000") @PathVariable UUID id) {
        log.info("Fetching customer with id: {}", id);
        return customerService.getCustomerById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Create a new customer", description = "Add a new customer to the system.")
    @ApiResponse(responseCode = "200", description = "Customer created successfully.")
    @PostMapping
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_CREATE + "')")
    @EmitEvent(id = "CUSTOMER_CUSTOMER_CREATE", apiVersion = "1")
    public ResponseEntity<CustomerDTO> createCustomer(
            @Parameter(description = "Customer object to be created") @RequestBody CustomerDTO customer) {
        log.info("Creating new customer: {}", customer);
        CustomerDTO saved = customerService.createCustomer(customer);
        return ResponseEntity.ok(saved);
    }

    @Operation(summary = "Update an existing customer", description = "Update the details of an existing customer.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Customer updated successfully."),
            @ApiResponse(responseCode = "404", description = "Customer not found.")
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_EDIT + "')")
    @EmitEvent(id = "CUSTOMER_CUSTOMER_UPDATE", apiVersion = "1")
    public ResponseEntity<CustomerDTO> updateCustomer(
            @Parameter(description = "ID of the customer to update", example = "123e4567-e89b-12d3-a456-426614174000") @PathVariable UUID id,
            @Parameter(description = "Updated customer object") @RequestBody CustomerDTO customer) {
        log.info("Updating customer with id: {}", id);
        return customerService.updateCustomer(id, customer)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Delete a customer", description = "Delete a customer by their unique ID.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Customer deleted successfully."),
            @ApiResponse(responseCode = "404", description = "Customer not found.")
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_DEACTIVATE + "')")
    public ResponseEntity<Void> deleteCustomer(
            @Parameter(description = "ID of the customer to delete", example = "123e4567-e89b-12d3-a456-426614174000") @PathVariable UUID id) {
        log.info("Deleting customer with id: {}", id);
        if (customerService.deleteCustomer(id)) {
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}
