package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import org.jspecify.annotations.NonNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request to create a Credit Memo.
 * 
 * Business Rules:
 * - originalInvoiceId must reference a finalized invoice
 * - creditAmount must not exceed invoice outstanding balance
 * - reasonCode is mandatory for audit
 * - justificationNote is optional but recommended
 */
public class CreateCreditMemoRequest {

    @JsonProperty("originalInvoiceId")
    @NotNull(message = "Original invoice ID is required")
    private UUID originalInvoiceId;

    @JsonProperty("creditAmount")
    @NotNull(message = "Credit amount is required")
    @DecimalMin(value = "0.01", message = "Credit amount must be positive")
    @Digits(integer = 15, fraction = 4, message = "Credit amount must have at most 15 integer and 4 fractional digits")
    private BigDecimal creditAmount;

    @JsonProperty("reasonCode")
    @NotBlank(message = "Reason code is required")
    @Size(min = 1, max = 50, message = "Reason code must be 1-50 characters")
    private String reasonCode;

    @JsonProperty("justificationNote")
    @Size(max = 1000, message = "Justification note must not exceed 1000 characters")
    private String justificationNote;

    // Constructors

    public CreateCreditMemoRequest() {
    }

    public CreateCreditMemoRequest(
            @NonNull UUID originalInvoiceId,
            @NonNull BigDecimal creditAmount,
            @NonNull String reasonCode,
            String justificationNote) {
        this.originalInvoiceId = originalInvoiceId;
        this.creditAmount = creditAmount;
        this.reasonCode = reasonCode;
        this.justificationNote = justificationNote;
    }

    // Getters and Setters

    public UUID getOriginalInvoiceId() {
        return originalInvoiceId;
    }

    public void setOriginalInvoiceId(@NonNull UUID originalInvoiceId) {
        this.originalInvoiceId = originalInvoiceId;
    }

    public BigDecimal getCreditAmount() {
        return creditAmount;
    }

    public void setCreditAmount(@NonNull BigDecimal creditAmount) {
        this.creditAmount = creditAmount;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(@NonNull String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getJustificationNote() {
        return justificationNote;
    }

    public void setJustificationNote(String justificationNote) {
        this.justificationNote = justificationNote;
    }
}
