package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One customer payment still waiting to be matched (#2502). Display fields are null when accounting
 * cannot resolve them, never an identifier rendered as text (P8, ADR-0064).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A customer payment with an unapplied amount, and the invoices it most likely pays")
public class UnappliedPaymentRow {

    @Schema(
            description = "Receivable payment identifier",
            example = "0199a000-0000-7000-8000-000000000101",
            requiredMode = REQUIRED)
    private UUID paymentId;

    @Schema(
            description = "Customer the payment belongs to",
            example = "0198a000-0000-7000-8000-000000000412",
            requiredMode = REQUIRED)
    private UUID customerId;

    @Schema(
            description = "Customer name from accounting's customer replica; null when unknown",
            example = "Rivera Trucking",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String customerDisplayName;

    @Schema(
            description = "Customer number from accounting's customer replica; null when unknown",
            example = "CUST-00412",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String customerReference;

    @Schema(
            description = "Settlement method as the payment fact sent it (CASH, CARD, ON_ACCOUNT, OTHER); null when"
                    + " the recording path carried none",
            example = "CARD",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String paymentMethod;

    @Schema(description = "When the payment cleared", example = "2026-10-04T15:12:09Z", requiredMode = REQUIRED)
    private Instant receivedAt;

    @Schema(description = "Payment currency (ISO 4217)", example = "USD", requiredMode = REQUIRED)
    private String currency;

    @Schema(description = "Amount the payment cleared for", example = "4615.00", requiredMode = REQUIRED)
    private BigDecimal totalAmount;

    @Schema(description = "Amount not yet applied or credited", example = "4615.00", requiredMode = REQUIRED)
    private BigDecimal unappliedAmount;

    @Schema(
            description = "Invoice the payment was taken against (its remittance reference); null when unknown",
            example = "0199a000-0000-7000-8000-000000001702",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private UUID sourceInvoiceId;

    @Schema(
            description = "Number of the invoice the payment was taken against; null when unknown",
            example = "INV-2026-01702",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    private String sourceInvoiceNumber;

    @Schema(description = "The invoices the payment most likely pays and why", requiredMode = REQUIRED)
    private PaymentMatchSuggestion suggestion;
}
