package com.positivity.customer.controller;

import com.positivity.customer.model.AbstractCustomer;
import com.positivity.customer.repository.CustomerRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@Slf4j
@Tag(name = "Customer API", description = "Operations related to customers")
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {
    
    private final CustomerRepository customerRepository;

    @Operation(summary = "Get all customers", description = "Retrieve a list of all customers.")
    @ApiResponse(responseCode = "200", description = "List of customers returned successfully.")
    @GetMapping
    public List<AbstractCustomer> getAllCustomers() {
        log.info("Fetching all customers");
        return customerRepository.findAll();
    }

    @Operation(summary = "Get customer by ID", description = "Retrieve a customer by their unique ID.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Customer found and returned."),
        @ApiResponse(responseCode = "404", description = "Customer not found.")
    })
    @GetMapping("/{id}")
    public ResponseEntity<AbstractCustomer> getCustomerById(
            @Parameter(description = "ID of the customer to retrieve", example = "1")
            @PathVariable Long id) {
        log.info("Fetching customer with id: {}", id);
        return customerRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Create a new customer", description = "Add a new customer to the system.")
    @ApiResponse(responseCode = "200", description = "Customer created successfully.")
    @PostMapping
    public ResponseEntity<AbstractCustomer> createCustomer(
            @Parameter(description = "Customer object to be created")
            @RequestBody AbstractCustomer customer) {
        log.info("Creating new customer: {}", customer);
        AbstractCustomer saved = customerRepository.save(customer);
        return ResponseEntity.ok(saved);
    }

    @Operation(summary = "Update an existing customer", description = "Update the details of an existing customer.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Customer updated successfully."),
        @ApiResponse(responseCode = "404", description = "Customer not found.")
    })
    @PutMapping("/{id}")
    public ResponseEntity<AbstractCustomer> updateCustomer(
            @Parameter(description = "ID of the customer to update", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Updated customer object")
            @RequestBody AbstractCustomer customer) {
        log.info("Updating customer with id: {}", id);
        return customerRepository.findById(id)
                .map(existing -> {
                    customer.setId(id);
                    AbstractCustomer updated = customerRepository.save(customer);
                    return ResponseEntity.ok(updated);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(summary = "Delete a customer", description = "Delete a customer by their unique ID.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Customer deleted successfully."),
        @ApiResponse(responseCode = "404", description = "Customer not found.")
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCustomer(
            @Parameter(description = "ID of the customer to delete", example = "1")
            @PathVariable Long id) {
        log.info("Deleting customer with id: {}", id);
        if (customerRepository.existsById(id)) {
            customerRepository.deleteById(id);
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}

