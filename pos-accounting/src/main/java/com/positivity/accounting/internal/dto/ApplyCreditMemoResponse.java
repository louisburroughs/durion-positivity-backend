package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import com.positivity.accounting.internal.enums.InvoiceStatus;
import lombok.experimental.Tolerate;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response from applying a Credit Memo to an invoice.
 * Returned by Invoice service to Accounting service.
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/131">Issue
 *      #131</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplyCreditMemoResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonProperty("invoiceId")
    @NonNull
    private UUID invoiceId;

    @JsonProperty("balanceBefore")
    @NonNull
    private BigDecimal balanceBefore;

    @JsonProperty("balanceAfter")
    @NonNull
    private BigDecimal balanceAfter;

    @JsonProperty("status")
    @NonNull
    private InvoiceStatus status;

    public void setStatus(@NonNull InvoiceStatus status) {
        if (status == null) {
            throw new IllegalStateException("Invoice status cannot be null");
        }
        if (status == InvoiceStatus.UNKNOWN) {
            throw new IllegalStateException("Invoice status cannot be UNKNOWN");
        }
        this.status = status;
    }

    @Tolerate
    public void setStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalStateException("Invoice status cannot be null or blank");
        }
        try {
            setStatus(InvoiceStatus.valueOf(status.trim()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Unknown invoice status: " + status, e);
        }
    }

    @JsonProperty("creditMemoApplied")
    @NonNull
    private BigDecimal creditMemoApplied;
}
