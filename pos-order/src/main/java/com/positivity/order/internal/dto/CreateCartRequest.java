package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
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
            description = "Shop location the cart is created at; drives order-number assignment. "
                    + "Optional when the terminal has an open register session (the session supplies "
                    + "the location); required otherwise.",
            example = "01960003-0000-7000-8000-000000000090",
            requiredMode = NOT_REQUIRED)
    private UUID locationId;

    @Schema(
            description = "Optional parking label for resumable drafts",
            example = "blue F-150 waiting on customer",
            requiredMode = NOT_REQUIRED)
    private String label;

    @Schema(
            description = "Optional order-level note",
            example = "Customer will pick up after 5pm",
            requiredMode = NOT_REQUIRED)
    private String generalNote;

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

    @Schema(
            description = "When this cart takes a deposit / down payment (odoo-parity story E4), the source "
                    + "document type it is held against; pos-invoice registers a deposit credit at checkout.",
            allowableValues = {"ESTIMATE", "WORKORDER", "ORDER"},
            requiredMode = NOT_REQUIRED)
    private String depositSourceType;

    @Schema(
            description = "Source document id the deposit is held against (required with depositSourceType).",
            example = "01960003-0000-7000-8000-000000000011",
            requiredMode = NOT_REQUIRED)
    private java.util.UUID depositSourceId;
}
