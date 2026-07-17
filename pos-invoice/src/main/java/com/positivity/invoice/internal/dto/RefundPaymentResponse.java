package com.positivity.invoice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.invoice.internal.enums.RefundReason;
import com.positivity.invoice.internal.enums.RefundStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Result of processing a refund against an invoice payment")
public class RefundPaymentResponse {

    @NotNull
    @Schema(
            description = "Unique identifier of the refund",
            example = "01960003-0000-7000-8000-000000000070",
            requiredMode = REQUIRED)
    private UUID refundId;

    @Schema(
            description = "Identifier of the invoice being refunded; absent for party-anchored standalone refunds",
            example = "01960003-0000-7000-8000-000000000040",
            requiredMode = NOT_REQUIRED)
    private UUID invoiceId;

    @Schema(
            description = "Identifier of the originating payment intent; absent for standalone refunds",
            example = "01960003-0000-7000-8000-000000000020",
            requiredMode = NOT_REQUIRED)
    private UUID paymentIntentId;

    @Schema(
            description = "Customer party associated with the refund — the anchor for standalone refunds,"
                    + " derived from the invoice for invoice- and payment-anchored refunds",
            example = "party-000123",
            requiredMode = NOT_REQUIRED)
    private String partyId;

    @Schema(description = "Refunded amount", example = "50.00", requiredMode = NOT_REQUIRED)
    private BigDecimal amount;

    @Schema(description = "Reason for the refund", example = "CUSTOMER_REQUEST", requiredMode = NOT_REQUIRED)
    private RefundReason reason;

    @Schema(
            description = "Free-text notes accompanying the refund",
            example = "Partial refund for unused service",
            requiredMode = NOT_REQUIRED)
    private String notes;

    @NotNull
    @Schema(description = "Current status of the refund", example = "COMPLETED", requiredMode = REQUIRED)
    private RefundStatus status;

    @Schema(
            description = "Gateway reference identifier for the refund",
            example = "re_3NXyZ2eZvKYlo2Ca",
            requiredMode = NOT_REQUIRED)
    private String gatewayReference;

    @Schema(
            description = "Optional correlation id to an external record (e.g. a warranty claim settlement)",
            example = "WC-2026-000042",
            requiredMode = NOT_REQUIRED)
    private String externalReference;

    @Schema(
            description = "Timestamp when the refund completed",
            example = "2026-01-17T10:15:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant completedAt;
}
