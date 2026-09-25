package com.positivity.invoice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.invoice.internal.enums.PaymentFlow;
import com.positivity.invoice.internal.enums.PaymentIntentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

/**
 * Read view of a payment intent, as returned by {@code listInvoicePayments} and
 * {@code getInvoicePayment} (#2226, #2215).
 *
 * <p>Deliberately narrower than the {@link com.positivity.invoice.internal.entity.PaymentIntent}
 * entity: {@code paymentToken} (tokenised card reference) and {@code gatewayResponse} (raw
 * gateway JSON, retained only for audit) never leave the service.
 */
@Data
@Schema(description = "Read view of a payment intent")
public class PaymentIntentResponse {

    @NotNull
    @Schema(
            description = "Payment intent identifier",
            example = "01960003-0000-7000-8000-000000000020",
            requiredMode = REQUIRED)
    private UUID paymentId;

    @NotNull
    @Schema(
            description = "Invoice the payment intent belongs to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID invoiceId;

    @NotNull
    @Schema(
            description = "Current lifecycle status of the payment intent",
            example = "CAPTURED",
            requiredMode = REQUIRED)
    private PaymentIntentStatus status;

    @NotNull
    @Schema(description = "SALE_CAPTURE or AUTH_ONLY", example = "SALE_CAPTURE", requiredMode = REQUIRED)
    private PaymentFlow paymentFlow;

    @Schema(description = "Amount authorized on the card", example = "149.99", requiredMode = NOT_REQUIRED)
    private BigDecimal authorizedAmount;

    @Schema(description = "Amount captured (funds moved)", example = "149.99", requiredMode = NOT_REQUIRED)
    private BigDecimal capturedAmount;

    @Schema(description = "Remainder voided after partial capture", example = "0.00", requiredMode = NOT_REQUIRED)
    private BigDecimal voidedRemainderAmount;

    @Schema(
            description = "Sum of non-FAILED refunds recorded against this payment intent",
            example = "0.00",
            requiredMode = NOT_REQUIRED)
    private BigDecimal refundedAmount;

    @Schema(
            description = "capturedAmount minus refundedAmount; null when the intent is not CAPTURED",
            example = "149.99",
            nullable = true,
            requiredMode = NOT_REQUIRED)
    private BigDecimal refundableAmount;

    @Schema(description = "Gateway provider identifier (e.g., stripe)", example = "stripe", requiredMode = NOT_REQUIRED)
    private String gatewayProvider;

    @Schema(
            description = "Opaque gateway transaction reference",
            example = "ch_3NXyZ2eZvKYlo2Ca",
            requiredMode = NOT_REQUIRED)
    private String gatewayReference;

    @NotNull
    @Schema(description = "When the payment intent was created", requiredMode = REQUIRED)
    private Instant createdAt;

    @NotNull
    @Schema(description = "When the payment intent was last updated", requiredMode = REQUIRED)
    private Instant updatedAt;
}
