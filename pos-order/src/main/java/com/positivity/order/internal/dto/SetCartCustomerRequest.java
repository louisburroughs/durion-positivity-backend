package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(
        description = "Sets or changes a cart's customer: exactly one of customerId (a registered customer) or "
                + "walkIn: true (the business's Walk-in customer, for a sale paid in full now)")
public class SetCartCustomerRequest {

    @Schema(
            description = "Registered customer to put on the cart. Omit when walkIn is true",
            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a70",
            requiredMode = NOT_REQUIRED)
    private UUID customerId;

    @Schema(
            description = "true to choose the Walk-in customer explicitly. Never applied by default",
            example = "false",
            requiredMode = NOT_REQUIRED)
    private Boolean walkIn;

    @Schema(
            description = "One of the customer's vehicles; when omitted the cart's vehicle is cleared. Not "
                    + "allowed together with walkIn",
            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a80",
            requiredMode = NOT_REQUIRED)
    private UUID vehicleId;
}
