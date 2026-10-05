package com.positivity.order.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
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

    @Schema(
            description = "Total of cash and card the cashier is taking now. Required for a walk-in cart (the "
                    + "customer is the business's Walk-in customer), where it must cover the final grand total "
                    + "computed at checkout; ignored for any other cart. Never negative",
            example = "84.37",
            minimum = "0",
            requiredMode = NOT_REQUIRED)
    private BigDecimal tenderedAmount;
}
