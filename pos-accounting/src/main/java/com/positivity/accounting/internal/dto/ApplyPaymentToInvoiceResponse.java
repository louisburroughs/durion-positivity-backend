package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.positivity.accounting.internal.enums.InvoiceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response after applying payment to invoice (from Invoice service).
 * Contains updated invoice state after payment application.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/114">Issue
 *      #114</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response after applying a payment to an invoice")
public class ApplyPaymentToInvoiceResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(
            description = "Identifier of the invoice the payment was applied to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @JsonProperty("invoiceId")
    @NotNull
    private UUID invoiceId;

    /**
     * New invoice status after payment application
     */
    @Schema(
            description = "Invoice status after payment application",
            example = "PARTIALLY_PAID",
            requiredMode = REQUIRED)
    @JsonProperty("status")
    @NotNull
    private InvoiceStatus status;

    /**
     * Invoice balance BEFORE payment application
     */
    @Schema(
            description = "Invoice balance before payment application",
            example = "1250.00",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("balanceBefore")
    private BigDecimal balanceBefore;

    /**
     * Invoice balance AFTER payment application
     */
    @Schema(description = "Invoice balance after payment application", example = "250.00", requiredMode = NOT_REQUIRED)
    @JsonProperty("balanceAfter")
    private BigDecimal balanceAfter;

    /**
     * Total amount paid on this invoice (cumulative)
     */
    @Schema(
            description = "Cumulative total amount paid on this invoice",
            example = "1000.00",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("totalPaid")
    private BigDecimal totalPaid;

    /**
     * Invoice total amount
     */
    @Schema(description = "Total amount of the invoice", example = "1250.00", requiredMode = NOT_REQUIRED)
    @JsonProperty("totalAmount")
    private BigDecimal totalAmount;

    /**
     * Timestamp of update
     */
    @Schema(
            description = "Timestamp of the invoice update (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("lastUpdated")
    private Instant lastUpdated;

    /**
     * Reference to payment application record
     */
    @Schema(
            description = "Identifier of the payment application record",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("paymentApplicationId")
    private UUID paymentApplicationId;
}
