package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

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
@Schema(description = "Restored invoice state returned after a payment application reversal")
public class ReversePaymentApplicationResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(
            description = "Identifier of the invoice whose payment was reversed",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("invoiceId")
    private UUID invoiceId;

    /**
     * Invoice status after reversal
     */
    @Schema(description = "Invoice status after the reversal", example = "PARTIALLY_PAID", requiredMode = NOT_REQUIRED)
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
    @Schema(description = "Invoice balance due before the reversal", example = "0.00", requiredMode = NOT_REQUIRED)
    @JsonProperty("balanceBefore")
    private BigDecimal balanceBefore;

    /**
     * Invoice balance AFTER reversal (restored)
     */
    @Schema(description = "Invoice balance due after the reversal (restored)", example = "1250.00", requiredMode = NOT_REQUIRED)
    @JsonProperty("balanceDue")
    private BigDecimal balanceDue;

    /**
     * Total amount paid on this invoice (after reversal)
     */
    @Schema(description = "Total amount paid on the invoice after the reversal", example = "0.00", requiredMode = NOT_REQUIRED)
    @JsonProperty("totalPaid")
    private BigDecimal totalPaid;

    /**
     * Reason for reversal
     */
    @Schema(description = "Reason recorded for the reversal", example = "Duplicate payment applied in error", requiredMode = NOT_REQUIRED)
    @JsonProperty("reason")
    private String reason;

    /**
     * Timestamp of reversal
     */
    @Schema(
            description = "Timestamp when the reversal occurred (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("reversedAt")
    private Instant reversedAt;
}
