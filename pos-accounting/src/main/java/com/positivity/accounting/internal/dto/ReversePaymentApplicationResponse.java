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
import lombok.experimental.Tolerate;

/**
 * Response after reversing payment application on invoice (from Invoice
 * service).
 * Contains restored invoice state after payment reversal.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/114">Issue
 *      #114</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReversePaymentApplicationResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonProperty("invoiceId")
    private UUID invoiceId;

    /**
     * Invoice status after reversal
     */
    @JsonProperty("status")
    private InvoiceStatus status;

    @Tolerate
    public void setStatus(String status) {
        if (status != null) {
            try {
                this.status = InvoiceStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                this.status = null;
            }
        }
    }

    /**
     * Invoice balance BEFORE reversal
     */
    @JsonProperty("balanceBefore")
    private BigDecimal balanceBefore;

    /**
     * Invoice balance AFTER reversal (restored)
     */
    @JsonProperty("balanceDue")
    private BigDecimal balanceDue;

    /**
     * Total amount paid on this invoice (after reversal)
     */
    @JsonProperty("totalPaid")
    private BigDecimal totalPaid;

    /**
     * Reason for reversal
     */
    @JsonProperty("reason")
    private String reason;

    /**
     * Timestamp of reversal
     */
    @JsonProperty("reversedAt")
    private Instant reversedAt;
}
