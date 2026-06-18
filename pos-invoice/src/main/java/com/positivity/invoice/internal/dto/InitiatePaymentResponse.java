package com.positivity.invoice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.invoice.internal.enums.PaymentIntentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;

/**
 * Response representing the created or updated payment intent.
 * Story #9.
 */
@Data
@Schema(description = "Payment intent result")
public class InitiatePaymentResponse {

    @NotNull
    @Schema(
            description = "Payment intent identifier",
            example = "01960003-0000-7000-8000-000000000020",
            requiredMode = REQUIRED)
    private UUID paymentIntentId;

    @NotNull
    @Schema(description = "Current lifecycle status of the payment intent", example = "AUTHORIZED", requiredMode = REQUIRED)
    private PaymentIntentStatus status;

    @Schema(description = "Amount authorized on the card", example = "149.99", requiredMode = NOT_REQUIRED)
    private BigDecimal authorizedAmount;

    @Schema(description = "Amount captured (funds moved)", example = "149.99", requiredMode = NOT_REQUIRED)
    private BigDecimal capturedAmount;

    @Schema(description = "Remainder voided after partial capture", example = "0.00", requiredMode = NOT_REQUIRED)
    private BigDecimal voidedRemainderAmount;

    @Schema(description = "Gateway provider identifier (e.g., stripe)", example = "stripe", requiredMode = NOT_REQUIRED)
    private String gatewayProvider;

    @Schema(
            description = "Raw gateway response JSON for audit",
            example = "{\"id\":\"pi_3NXyZ2eZvKYlo2Ca\",\"status\":\"requires_capture\"}",
            requiredMode = NOT_REQUIRED)
    private String gatewayResponse;
}
