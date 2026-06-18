package com.positivity.customer.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    @Schema(
            description = "Unique identifier of the customer",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private UUID id;

    @Size(max = 50)
    @Schema(
            description = "Unique customer number",
            example = "CUST-1001",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String customerNumber;

    @NotBlank
    @Size(max = 100)
    @Schema(
            description = "Last name of the customer",
            example = "Doe",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String lastName;

    @NotBlank
    @Size(max = 100)
    @Schema(
            description = "First name of the customer",
            example = "John",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String firstName;

    @Size(max = 255)
    @Schema(
            description = "Primary address label or identifier for the customer",
            example = "123 Main St, Springfield",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String primaryAddress;

    @Builder.Default
    @Schema(
            description = "List of vehicle VINs associated with the customer",
            example = "[\"1HGCM82633A004352\"]",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private List<String> vehicleVins = new ArrayList<>();

    @Size(max = 50)
    @Schema(
            description = "Type of customer (e.g., 'retail', 'commercial')",
            example = "retail",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String customerType;
}
