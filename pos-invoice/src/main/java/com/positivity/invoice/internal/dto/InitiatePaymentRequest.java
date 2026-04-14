package com.positivity.invoice.internal.dto;

import com.positivity.invoice.internal.enums.PaymentFlow;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import lombok.Data;

/**
 * Request to initiate a card payment against an invoice.
 *
 * <p>
 * The {@code paymentToken} field must contain a tokenised card reference —
 * no PAN or CVV may be sent or stored (AC8, Story #9).
 *
 * Story #9.
 */
@Data
@Schema(description = "Request to initiate a card payment")
public class InitiatePaymentRequest {

    @NotNull
    @Schema(
            description = "Payment flow: SALE_CAPTURE (default one-step) or AUTH_ONLY (two-step)",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private PaymentFlow paymentFlow;

    @NotNull
    @Positive
    @Schema(description = "Payment amount (must be positive)", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal amount;

    @NotBlank
    @Schema(description = "Client-provided idempotency key for safe retry", requiredMode = Schema.RequiredMode.REQUIRED)
    private String idempotencyKey;

    @NotBlank
    @Schema(description = "Tokenised card reference (no PAN)", requiredMode = Schema.RequiredMode.REQUIRED)
    private String paymentToken;
}
