package com.positivity.customer.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Data Transfer Object for Customer API operations.
 * Used for both request and response payloads in the Customer API.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Customer data transfer object for API operations")
public class CustomerDTO {

    @Schema(description = "Unique identifier of the customer", example = "1")
    private Long id;

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

    @Builder.Default
    @Schema(description = "List of vehicle VINs associated with the customer")
    private List<String> vehicleVins = new ArrayList<>();

    @Schema(description = "Type of customer (e.g., 'retail', 'commercial')")
    private String customerType;
}
