package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.positivity.accounting.internal.enums.InvoiceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Tolerate;
import org.jspecify.annotations.NonNull;

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
@Schema(description = "Response after applying a credit memo to an invoice")
public class ApplyCreditMemoResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(
            description = "Identifier of the invoice the credit memo was applied to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @JsonProperty("invoiceId")
    @NotNull
    @NonNull
    private UUID invoiceId;

    @Schema(
            description = "Invoice balance before the credit memo was applied",
            example = "1250.00",
            requiredMode = REQUIRED)
    @JsonProperty("balanceBefore")
    @NotNull
    @NonNull
    private BigDecimal balanceBefore;

    @Schema(
            description = "Invoice balance after the credit memo was applied",
            example = "0.00",
            requiredMode = REQUIRED)
    @JsonProperty("balanceAfter")
    @NotNull
    @NonNull
    private BigDecimal balanceAfter;

    @Schema(
            description = "Invoice status after the credit memo was applied",
            example = "PAID_IN_FULL",
            requiredMode = REQUIRED)
    @JsonProperty("status")
    @NotNull
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

    @Schema(
            description = "Amount of the credit memo applied to the invoice",
            example = "1250.00",
            requiredMode = REQUIRED)
    @JsonProperty("creditMemoApplied")
    @NotNull
    @NonNull
    private BigDecimal creditMemoApplied;
}
