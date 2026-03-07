package com.positivity.invoice.internal.dto;

import com.positivity.invoice.internal.enums.PaymentIntentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response representing the created or updated payment intent.
 * Story #9.
 */
@Data
@Schema(description = "Payment intent result")
public class InitiatePaymentResponse {

    @Schema(description = "Payment intent identifier")
    private UUID paymentIntentId;

    @Schema(description = "Current lifecycle status of the payment intent")
    private PaymentIntentStatus status;

    @Schema(description = "Amount authorized on the card")
    private BigDecimal authorizedAmount;

    @Schema(description = "Amount captured (funds moved)")
    private BigDecimal capturedAmount;

    @Schema(description = "Remainder voided after partial capture")
    private BigDecimal voidedRemainderAmount;

    @Schema(description = "Gateway provider identifier (e.g., stripe)")
    private String gatewayProvider;

    @Schema(description = "Raw gateway response JSON for audit")
    private String gatewayResponse;
}