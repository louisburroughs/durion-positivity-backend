package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Optional checkout options")
public class CheckoutRequest {

    @Schema(
            description = "Tender intent: DEFAULT (asynchronous settlement via payments) or ON_ACCOUNT (charge a "
                    + "validated commercial customer's account; requires order:order:charge_on_account and the "
                    + "order completes with the balance carried by the AR invoice)",
            example = "ON_ACCOUNT",
            requiredMode = NOT_REQUIRED)
    private String tenderType;
}
