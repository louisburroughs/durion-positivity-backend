package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.InvoiceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.*;

/**
 * Response DTO for payment application operations.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/114">Issue
 *      #114</a>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
@Schema(description = "Result of applying a payment to one or more invoices")
public class PaymentApplicationResponse {

    @Schema(
            description = "Identifier of the payment that was applied",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID paymentId;

    @Schema(
            description = "Identifier of the customer who made the payment",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID customerId;

    @Schema(description = "Currency code (ISO 4217)", example = "USD", requiredMode = NOT_REQUIRED)
    private String currency;

    @Schema(description = "Total payment amount", example = "1250.00", requiredMode = NOT_REQUIRED)
    private BigDecimal totalAmount;

    @Schema(description = "Amount of the payment applied to invoices", example = "1000.00", requiredMode = NOT_REQUIRED)
    private BigDecimal appliedAmount;

    @Schema(
            description = "Amount of the payment remaining after application",
            example = "250.00",
            requiredMode = NOT_REQUIRED)
    private BigDecimal remainingAmount;

    @Schema(description = "Per-invoice application details", requiredMode = NOT_REQUIRED)
    private List<ApplicationDetail> applications;

    @Schema(description = "Customer credit created from any overpayment", requiredMode = NOT_REQUIRED)
    private CustomerCreditInfo customerCredit;

    @Schema(
            description = "Timestamp when the payment was applied (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant applicationTimestamp;

    @Schema(
            description = "Idempotency key echoed from the application request",
            example = "pay-apply-20260618-001",
            requiredMode = NOT_REQUIRED)
    private String applicationRequestId;

    /**
     * Details of a single invoice application.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @ToString
    @Schema(description = "Details of a single invoice application")
    public static class ApplicationDetail {
        @Schema(
                description = "Identifier of the payment application record",
                example = "01960003-0000-7000-8000-000000000010",
                requiredMode = NOT_REQUIRED)
        private UUID paymentApplicationId;

        @Schema(
                description = "Identifier of the invoice the payment was applied to",
                example = "01960003-0000-7000-8000-000000000011",
                requiredMode = NOT_REQUIRED)
        private UUID invoiceId;

        @Schema(description = "Amount applied to this invoice", example = "1000.00", requiredMode = NOT_REQUIRED)
        private BigDecimal appliedAmount;

        @Schema(
                description = "Invoice balance before this application",
                example = "1000.00",
                requiredMode = NOT_REQUIRED)
        private BigDecimal invoiceBalanceBefore;

        @Schema(
                description = "Invoice balance after this application",
                example = "0.00",
                requiredMode = NOT_REQUIRED)
        private BigDecimal invoiceBalanceAfter;

        @Schema(
                description = "Invoice status after this application",
                example = "PAID_IN_FULL",
                requiredMode = NOT_REQUIRED)
        private InvoiceStatus invoiceStatus;
    }

    /**
     * Customer credit created from overpayment.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @ToString
    @Schema(description = "Customer credit created from an overpayment")
    public static class CustomerCreditInfo {
        @Schema(
                description = "Identifier of the customer credit record",
                example = "01960003-0000-7000-8000-000000000020",
                requiredMode = NOT_REQUIRED)
        private UUID creditId;

        @Schema(description = "Credit amount created from overpayment", example = "250.00", requiredMode = NOT_REQUIRED)
        private BigDecimal amount;

        @Schema(
                description = "Timestamp when the credit was created (ISO 8601)",
                example = "2026-06-18T08:00:00Z",
                requiredMode = NOT_REQUIRED)
        private Instant createdAt;
    }
}
