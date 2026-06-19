package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Request payload for creating a new cart")
public class CreateCartRequest {

    @Schema(
            description = "Identifier of the clerk creating the cart",
            example = "01960003-0000-7000-8000-000000000050",
            requiredMode = REQUIRED)
    @NotBlank
    private String clerkId;

    @Schema(
            description = "Identifier of the terminal where the cart is created",
            example = "01960003-0000-7000-8000-000000000060",
            requiredMode = REQUIRED)
    @NotBlank
    private String terminalId;

    @Schema(
            description = "Identifier of the customer associated with the cart, when known",
            example = "01960003-0000-7000-8000-000000000070",
            requiredMode = NOT_REQUIRED)
    private String customerId;

    @Schema(
            description = "Identifier of the vehicle associated with the cart, when applicable",
            example = "01960003-0000-7000-8000-000000000080",
            requiredMode = NOT_REQUIRED)
    private String vehicleId;
}
