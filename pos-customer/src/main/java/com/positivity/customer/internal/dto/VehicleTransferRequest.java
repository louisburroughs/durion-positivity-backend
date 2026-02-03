package com.positivity.customer.internal.dto;

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
public class VehicleTransferRequest {

    /**
     * Target customer ID to transfer the vehicle to.
     */
    @NonNull
    private String targetCustomerId;

    /**
     * Optional notes about the transfer.
     */
    private String notes;
}
