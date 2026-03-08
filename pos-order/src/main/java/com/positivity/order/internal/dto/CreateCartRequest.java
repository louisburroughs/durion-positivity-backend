package com.positivity.order.internal.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateCartRequest {

    @NotBlank
    private String clerkId;

    @NotBlank
    private String terminalId;

    private String customerId;

    private String vehicleId;
}
