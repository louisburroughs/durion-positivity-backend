package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

/**
 * Request DTO for transferring vehicle ownership between customers.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to transfer vehicle ownership between customers")
public class VehicleTransferRequest {

    /**
     * Target customer ID to transfer the vehicle to.
     */
    @Schema(
            description = "Target customer ID to transfer the vehicle to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NonNull
    @NotBlank
    @Size(max = 100)
    private String targetCustomerId;

    /**
     * Optional notes about the transfer.
     */
    @Schema(
            description = "Optional notes about the transfer",
            example = "Vehicle reassigned to fleet account",
            requiredMode = NOT_REQUIRED)
    @Size(max = 1000)
    private String notes;
}
