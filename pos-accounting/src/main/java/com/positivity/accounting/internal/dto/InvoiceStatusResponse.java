package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Tolerate;

/**
 * DTO for invoice status response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Invoice payment status response")
public class InvoiceStatusResponse {

    @Schema(description = "Invoice UUID", example = "01960003-0000-7000-8000-000000000001", requiredMode = REQUIRED)
    @NotNull
    private UUID invoiceId;

    @Schema(description = "Payment status of the invoice", example = "PARTIALLY_PAID", requiredMode = REQUIRED)
    @NotNull
    private PaymentStatus status;

    @Schema(description = "Total amount paid to date", example = "1250.00", requiredMode = NOT_REQUIRED)
    private BigDecimal totalPaid;

    @Schema(description = "Total invoice amount", example = "1250.00", requiredMode = NOT_REQUIRED)
    private BigDecimal invoiceTotal;

    @Schema(
            description = "Remaining balance (invoice total minus total paid)",
            example = "0.00",
            requiredMode = NOT_REQUIRED)
    private BigDecimal remainingBalance;

    @Schema(
            description = "Reference of the latest payment transaction",
            example = "TXN-2026-000123",
            requiredMode = NOT_REQUIRED)
    private String latestTransactionReference;

    @Schema(description = "Last updated timestamp", example = "2026-06-18T08:00:00Z", requiredMode = NOT_REQUIRED)
    private Instant lastUpdated;

    /**
     * Convenience constructor that computes remainingBalance from invoiceTotal and
     * totalPaid.
     */
    @Tolerate
    public InvoiceStatusResponse(
            UUID invoiceId,
            PaymentStatus status,
            BigDecimal totalPaid,
            BigDecimal invoiceTotal,
            String latestTransactionReference,
            Instant lastUpdated) {
        this.invoiceId = invoiceId;
        this.status = status;
        this.totalPaid = totalPaid;
        this.invoiceTotal = invoiceTotal;
        this.remainingBalance = invoiceTotal.subtract(totalPaid);
        this.latestTransactionReference = latestTransactionReference;
        this.lastUpdated = lastUpdated;
    }
}
