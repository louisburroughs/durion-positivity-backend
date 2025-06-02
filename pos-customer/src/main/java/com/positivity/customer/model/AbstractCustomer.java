package com.positivity.customer.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "customer_type")
@Schema(description = "Abstract base class for a customer in the POS system.")
public abstract class AbstractCustomer implements Customer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Schema(description = "Unique identifier of the customer", example = "1")
    private Long id;

    @Column(unique = true, nullable = false)
    @Schema(description = "Unique customer number", example = "CUST-1001")
    private String customerNumber;

    @Schema(description = "Last name of the customer", example = "Doe")
    private String lastName;

    @Schema(description = "First name of the customer", example = "John")
    private String firstName;

    @Schema(description = "Phone number of the customer", example = "+1-555-1234")
    private String phoneNumber;

    @Schema(description = "Email address of the customer", example = "john.doe@example.com")
    private String email;

    @ElementCollection
    @Schema(description = "List of vehicle VINs associated with the customer")
    private List<String> vehicleVins;
}

