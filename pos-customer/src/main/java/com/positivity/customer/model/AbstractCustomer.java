package com.positivity.customer.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "customer_type")
@Schema(description = "Abstract base class for an individual customer (person). Use Party for organizations.")
public abstract class AbstractCustomer implements Customer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "Unique identifier of the customer", example = "1")
    private Long id;

    @Column(unique = true, nullable = false)
    @Schema(description = "Unique customer number", example = "CUST-1001")
    private String customerNumber;

    @NotBlank
    @Schema(description = "Last name of the customer", example = "Doe")
    private String lastName;

    @NotBlank
    @Schema(description = "First name of the customer", example = "John")
    private String firstName;

    @Schema(description = "Phone number of the customer", example = "+1-555-1234")
    private String phoneNumber;

    @Email
    @Schema(description = "Email address of the customer", example = "john.doe@example.com")
    private String email;

    @Schema(description = "Primary address label or identifier for the customer", example = "123 Main St, Springfield")
    private String primaryAddress;

    @ElementCollection
    @Schema(description = "List of vehicle VINs associated with the customer")
    private List<String> vehicleVins = new ArrayList<>();

    @PrePersist
    @PreUpdate
    private void validateCustomer() {
        if (firstName == null || firstName.isBlank()) {
            throw new IllegalStateException("firstName is required for a customer");
        }
        if (lastName == null || lastName.isBlank()) {
            throw new IllegalStateException("lastName is required for a customer");
        }
    }
}
