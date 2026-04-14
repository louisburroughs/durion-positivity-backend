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
import org.jspecify.annotations.NonNull;

/**
 * Invoice details retrieved from Invoice service.
 * Contains state necessary for payment application validation.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/114">Issue
 *      #114</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceDetails implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonProperty("invoiceId")
    @NonNull
    private UUID invoiceId;

    @JsonProperty("customerId")
    @NonNull
    private UUID customerId;

    @JsonProperty("invoiceNumber")
    private String invoiceNumber;

    /**
     * Invoice status (e.g., OPEN, PARTIALLY_PAID, PAID_IN_FULL, VOIDED, CANCELLED)
     */
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

    /**
     * Total invoice amount
     */
    @JsonProperty("totalAmount")
    @NonNull
    private BigDecimal totalAmount;

    /**
     * Remaining amount due (after all payments applied)
     */
    @JsonProperty("balanceDue")
    @NonNull
    private BigDecimal balanceDue;

    /**
     * Currency code (e.g., USD, EUR)
     */
    @JsonProperty("currency")
    @NonNull
    private String currency;

    /**
     * Invoice creation date
     */
    @JsonProperty("invoiceDate")
    private Instant invoiceDate;

    /**
     * Invoice due date
     */
    @JsonProperty("dueDate")
    private Instant dueDate;

    /**
     * Total amount paid to date (all payments combined)
     */
    @JsonProperty("totalPaid")
    private BigDecimal totalPaid;

    /**
     * Last modified timestamp
     */
    @JsonProperty("lastModified")
    private Instant lastModified;
}
