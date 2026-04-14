package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.PaymentOutcomeType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for processing payment outcome updates")
public class PaymentOutcomeRequest {

    @NotNull(message = "invoiceId is required")
    @Schema(
            description = "Invoice UUID",
            example = "01936e5b-4567-7a3d-8b6e-1a2345678901",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID invoiceId;
    /**
     * Primary idempotency key — matches transactionId on the payment gateway
     * receipt.
     */
    @Size(max = 100, message = "transactionId must not exceed 100 characters")
    @Schema(description = "Gateway transaction identifier (preferred idempotency key)", example = "txn_abc123xyz")
    private String transactionId;
    /** Fallback idempotency key when transactionId is absent. */
    @Size(max = 100, message = "idempotencyKey must not exceed 100 characters")
    @Schema(
            description = "Fallback idempotency key when transactionId is unavailable",
            example = "pay-outcome-20260115-001")
    private String idempotencyKey;

    @NotNull(message = "amountMinor is required")
    @Positive(message = "amountMinor must be greater than zero")
    @Schema(
            description = "Payment amount in minor units (e.g., cents)",
            example = "125000",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Long amountMinor;

    @NotBlank(message = "currency is required")
    @Size(min = 3, max = 3, message = "currency must be a 3-character ISO code")
    @Schema(description = "Currency code (ISO 4217)", example = "USD", requiredMode = Schema.RequiredMode.REQUIRED)
    private String currency;

    @NotNull(message = "outcomeType is required")
    @Schema(
            description = "Payment outcome classification",
            example = "FULL_PAYMENT",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private PaymentOutcomeType outcomeType;

    @Schema(
            description = "Customer UUID. Required for OVERPAYMENT outcomes.",
            example = "01936e5a-7890-7a3d-8b6e-2b3456789012")
    private UUID customerId;

    @AssertTrue(message = "Either transactionId or idempotencyKey must be provided")
    public boolean hasIdempotencyReference() {
        return (transactionId != null && !transactionId.isBlank())
                || (idempotencyKey != null && !idempotencyKey.isBlank());
    }

    @AssertTrue(message = "customerId is required when outcomeType is OVERPAYMENT")
    public boolean isCustomerPresentForOverpayment() {
        return outcomeType != PaymentOutcomeType.OVERPAYMENT || customerId != null;
    }
}
