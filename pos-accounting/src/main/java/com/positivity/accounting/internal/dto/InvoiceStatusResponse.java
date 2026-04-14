package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.PaymentStatus;
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
public class InvoiceStatusResponse {

    private UUID invoiceId;
    private PaymentStatus status;
    private BigDecimal totalPaid;
    private BigDecimal invoiceTotal;
    private BigDecimal remainingBalance;
    private String latestTransactionReference;
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
