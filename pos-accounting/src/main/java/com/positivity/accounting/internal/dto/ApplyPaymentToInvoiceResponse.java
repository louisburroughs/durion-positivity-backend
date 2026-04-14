package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.positivity.accounting.internal.enums.InvoiceStatus;
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
public class ApplyPaymentToInvoiceResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonProperty("invoiceId")
    private UUID invoiceId;

    /**
     * New invoice status after payment application
     */
    @JsonProperty("status")
    private InvoiceStatus status;

    /**
     * Invoice balance BEFORE payment application
     */
    @JsonProperty("balanceBefore")
    private BigDecimal balanceBefore;

    /**
     * Invoice balance AFTER payment application
     */
    @JsonProperty("balanceAfter")
    private BigDecimal balanceAfter;

    /**
     * Total amount paid on this invoice (cumulative)
     */
    @JsonProperty("totalPaid")
    private BigDecimal totalPaid;

    /**
     * Invoice total amount
     */
    @JsonProperty("totalAmount")
    private BigDecimal totalAmount;

    /**
     * Timestamp of update
     */
    @JsonProperty("lastUpdated")
    private Instant lastUpdated;

    /**
     * Reference to payment application record
     */
    @JsonProperty("paymentApplicationId")
    private UUID paymentApplicationId;
}
