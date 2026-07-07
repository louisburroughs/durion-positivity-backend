package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.positivity.accounting.internal.enums.InvoiceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Invoice details required for payment application validation")
public class InvoiceDetails implements Serializable {
    private static final long serialVersionUID = 1L;

    @JsonProperty("invoiceId")
    @NonNull
    @Schema(description = "Invoice UUID", example = "01960003-0000-7000-8000-000000000001", requiredMode = REQUIRED)
    private UUID invoiceId;

    @JsonProperty("customerId")
    @NonNull
    @Schema(description = "Customer UUID", example = "01960003-0000-7000-8000-000000000002", requiredMode = REQUIRED)
    private UUID customerId;

    @JsonProperty("invoiceNumber")
    @Schema(description = "Human-readable invoice number", example = "INV-2026-000123", requiredMode = NOT_REQUIRED)
    private String invoiceNumber;

    /**
     * Invoice status (e.g., OPEN, PARTIALLY_PAID, PAID_IN_FULL, VOIDED, CANCELLED)
     */
    @JsonProperty("status")
    @NonNull
    @Schema(
            description = "Invoice status (OPEN, PARTIALLY_PAID, PAID_IN_FULL, VOIDED, CANCELLED)",
            example = "OPEN",
            requiredMode = REQUIRED)
    private InvoiceStatus status;

    public void setStatus(InvoiceStatus status) {
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
    @Schema(description = "Total invoice amount", example = "1250.00", requiredMode = REQUIRED)
    private BigDecimal totalAmount;

    /**
     * Remaining amount due (after all payments applied)
     */
    @JsonProperty("balanceDue")
    @NonNull
    @Schema(
            description = "Remaining amount due after all payments applied",
            example = "1250.00",
            requiredMode = REQUIRED)
    private BigDecimal balanceDue;

    /**
     * Currency code (e.g., USD, EUR)
     */
    @JsonProperty("currency")
    @NonNull
    @Schema(description = "ISO currency code", example = "USD", requiredMode = REQUIRED)
    private String currency;

    /**
     * Invoice creation date
     */
    @JsonProperty("invoiceDate")
    @Schema(description = "Invoice creation date", example = "2026-06-18T08:00:00Z", requiredMode = NOT_REQUIRED)
    private Instant invoiceDate;

    /**
     * Invoice due date
     */
    @JsonProperty("dueDate")
    @Schema(description = "Invoice due date", example = "2026-06-18T08:00:00Z", requiredMode = NOT_REQUIRED)
    private Instant dueDate;

    /**
     * Total amount paid to date (all payments combined)
     */
    @JsonProperty("totalPaid")
    @Schema(description = "Total amount paid to date", example = "0.00", requiredMode = NOT_REQUIRED)
    private BigDecimal totalPaid;

    /**
     * Last modified timestamp
     */
    @JsonProperty("lastModified")
    @Schema(description = "Last modified timestamp", example = "2026-06-18T08:00:00Z", requiredMode = NOT_REQUIRED)
    private Instant lastModified;
}
