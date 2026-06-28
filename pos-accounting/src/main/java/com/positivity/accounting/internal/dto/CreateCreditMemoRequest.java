package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Request to create a Credit Memo.
 *
 * Business Rules:
 * - originalInvoiceId must reference a finalized invoice
 * - creditAmount must not exceed invoice outstanding balance
 * - reasonCode is mandatory for audit
 * - justificationNote is optional but recommended
 */
@Schema(description = "Request to create a credit memo against a finalized invoice")
public class CreateCreditMemoRequest {

    @JsonProperty("originalInvoiceId")
    @NotNull(message = "Original invoice ID is required")
    @Schema(
            description = "Identifier of the finalized invoice the credit memo references",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID originalInvoiceId;

    @JsonProperty("creditAmount")
    @NotNull(message = "Credit amount is required")
    @DecimalMin(value = "0.01", message = "Credit amount must be positive")
    @Digits(integer = 15, fraction = 4, message = "Credit amount must have at most 15 integer and 4 fractional digits")
    @Schema(
            description = "Credit amount; must not exceed the invoice outstanding balance",
            example = "1250.00",
            requiredMode = REQUIRED)
    private BigDecimal creditAmount;

    @JsonProperty("reasonCode")
    @NotBlank(message = "Reason code is required")
    @Size(max = 50, message = "Reason code must be 1-50 characters")
    @Schema(
            description = "Reason code for the credit memo (mandatory for audit)",
            example = "RETURN",
            requiredMode = REQUIRED)
    private String reasonCode;

    @JsonProperty("justificationNote")
    @Size(max = 1000, message = "Justification note must not exceed 1000 characters")
    @Schema(
            description = "Optional justification note explaining the credit",
            example = "Customer returned defective parts",
            requiredMode = NOT_REQUIRED)
    private String justificationNote;

    // Constructors

    public CreateCreditMemoRequest() {}

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
