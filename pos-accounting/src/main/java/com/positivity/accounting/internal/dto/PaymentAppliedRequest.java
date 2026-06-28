package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for payment applied request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload recording a payment applied to an invoice")
public class PaymentAppliedRequest {

    @Schema(
            description = "Identifier of the invoice the payment is applied to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull(message = "Invoice ID is required")
    private UUID invoiceId;

    @Schema(
            description = "External transaction reference for the payment",
            example = "txn_abc123xyz",
            requiredMode = REQUIRED)
    @NotBlank(message = "Transaction reference is required")
    private String transactionReference;

    @Schema(description = "Payment amount applied", example = "1250.00", requiredMode = REQUIRED)
    @NotNull(message = "Payment amount is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Payment amount must be positive")
    private BigDecimal paymentAmount;

    @Schema(description = "Total amount due on the invoice", example = "1250.00", requiredMode = REQUIRED)
    @NotNull(message = "Invoice total is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Invoice total must be positive")
    private BigDecimal invoiceTotal;

    @Schema(
            description = "Idempotency key preventing duplicate application",
            example = "pay-applied-20260618-001",
            requiredMode = REQUIRED)
    @NotBlank(message = "Idempotency key is required")
    private String idempotencyKey;

    @Schema(description = "Whether the payment failed", example = "false", requiredMode = NOT_REQUIRED)
    @Builder.Default
    private boolean paymentFailed = false;
}
