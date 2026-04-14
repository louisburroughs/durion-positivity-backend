package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.dto.CustomerDTO;
import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.internal.service.CommercialPartyServiceImpl;
import com.positivity.customer.internal.service.PersonPartyServiceImpl;
import com.positivity.customer.service.CustomerService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@Tag(name = "Customer API", description = "Operations related to customers")
@RestController
@RequestMapping("/v1/crm")
public class CustomerController {

    private static final String COMMERCIAL = "COMMERCIAL";

    private final CustomerService commercialService;
    private final CustomerService personService;

    public CustomerController(PersonPartyServiceImpl personService, CommercialPartyServiceImpl commercialService) {
        this.commercialService = commercialService;
        this.personService = personService;
    }

    @Operation(
            summary = "Get all customers",
            description =
                    "Retrieve a paginated list of customers by type (PERSON or COMMERCIAL). Defaults to PERSON customers if no type specified.")
    @ApiResponse(responseCode = "200", description = "Page of customers returned successfully.")
    @GetMapping
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
    public Page<CustomerDTO> getAllCustomers(
            @Parameter(description = "Customer type filter: PERSON or COMMERCIAL", example = "PERSON")
                    @RequestParam(required = false, defaultValue = "PERSON")
                    String customerType,
            @Parameter(description = "Pagination parameters (page, size, sort)")
                    @PageableDefault(size = 20, sort = "lastName")
                    Pageable pageable) {
        log.info("Fetching customers of type: {} with paging: {}", customerType, pageable);

        if (COMMERCIAL.equalsIgnoreCase(customerType)) {
            return commercialService.getAllCustomers(pageable);
        } else {
            return personService.getAllCustomers(pageable);
        }
    }

    @Operation(summary = "Get customer by ID", description = "Retrieve a customer by their unique ID.")
    @ApiResponse(responseCode = "200", description = "Customer found and returned.")
    @ApiResponse(responseCode = "404", description = "Customer not found.")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
    public ResponseEntity<CustomerDTO> getCustomerById(
            @Parameter(description = "ID of the customer to retrieve", example = "123e4567-e89b-12d3-a456-426614174000")
                    @PathVariable
                    UUID id) {
        log.info("Fetching customer with id: {}", id);

        return commercialService
                .getCustomerById(id)
                .or(() -> personService.getCustomerById(id))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Create a new customer", description = "Add a new customer to the system.")
    @ApiResponse(responseCode = "201", description = "Customer created successfully.")
    @PostMapping
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_CREATE + "')")
    @EmitEvent(id = "CUSTOMER_CUSTOMER_CREATE", apiVersion = "1")
    public ResponseEntity<CustomerDTO> createCustomer(
            @Parameter(description = "Customer object to be created") @RequestBody CustomerDTO customer) {
        log.info("Creating new customer: {}", customer);
        CustomerService service =
                COMMERCIAL.equalsIgnoreCase(customer.getCustomerType()) ? commercialService : personService;
        CustomerDTO saved = service.createCustomer(customer);
        return ResponseEntity.status(201).body(saved);
    }

    @Operation(summary = "Update an existing customer", description = "Update the details of an existing customer.")
    @ApiResponse(responseCode = "200", description = "Customer updated successfully.")
    @ApiResponse(responseCode = "404", description = "Customer not found.")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_EDIT + "')")
    @EmitEvent(id = "CUSTOMER_CUSTOMER_UPDATE", apiVersion = "1")
    public ResponseEntity<CustomerDTO> updateCustomer(
            @Parameter(description = "ID of the customer to update", example = "123e4567-e89b-12d3-a456-426614174000")
                    @PathVariable
                    UUID id,
            @Parameter(description = "Updated customer object") @RequestBody CustomerDTO customer) {
        log.info("Updating customer with id: {}", id);
        CustomerService service =
                COMMERCIAL.equalsIgnoreCase(customer.getCustomerType()) ? commercialService : personService;
        return service.updateCustomer(id, customer)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Delete a customer", description = "Delete a customer by their unique ID.")
    @ApiResponse(responseCode = "204", description = "Customer deleted successfully.")
    @ApiResponse(responseCode = "404", description = "Customer not found.")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_DEACTIVATE + "')")
    @EmitEvent(id = "CUSTOMER_CUSTOMER_DELETE", apiVersion = "1")
    public ResponseEntity<Void> deleteCustomer(
            @Parameter(description = "ID of the customer to delete", example = "123e4567-e89b-12d3-a456-426614174000")
                    @PathVariable
                    UUID id) {
        log.info("Deleting customer with id: {}", id);
        if (commercialService.deleteCustomer(id) || personService.deleteCustomer(id)) {
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}
