package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.positivity.accounting.internal.enums.CreditMemoStatus;
import org.jspecify.annotations.NonNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response containing Credit Memo details.
 */
public class CreditMemoResponse {

    @JsonProperty("creditMemoId")
    private UUID creditMemoId;

    @JsonProperty("originalInvoiceId")
    private UUID originalInvoiceId;

    @JsonProperty("customerId")
    private UUID customerId;

    @JsonProperty("creditAmount")
    private BigDecimal creditAmount;

    @JsonProperty("taxAmountReversed")
    private BigDecimal taxAmountReversed;

    @JsonProperty("totalAmount")
    private BigDecimal totalAmount;

    @JsonProperty("reasonCode")
    private String reasonCode;

    @JsonProperty("justificationNote")
    private String justificationNote;

    @JsonProperty("status")
    private CreditMemoStatus status;

    @JsonProperty("creationTimestamp")
    private Instant creationTimestamp;

    @JsonProperty("postedTimestamp")
    private Instant postedTimestamp;

    @JsonProperty("createdByUserId")
    private String createdByUserId;

    @JsonProperty("priorPeriodAdjustment")
    private Boolean priorPeriodAdjustment;

    @JsonProperty("originalPeriodId")
    private String originalPeriodId;

    @JsonProperty("currency")
    private String currency;

    @JsonProperty("invoiceBalanceAfter")
    private BigDecimal invoiceBalanceAfter;

    // Constructors

    public CreditMemoResponse() {
    }

    // Getters and Setters

    public UUID getCreditMemoId() {
        return creditMemoId;
    }

    public void setCreditMemoId(@NonNull UUID creditMemoId) {
        this.creditMemoId = creditMemoId;
    }

    public UUID getOriginalInvoiceId() {
        return originalInvoiceId;
    }

    public void setOriginalInvoiceId(@NonNull UUID originalInvoiceId) {
        this.originalInvoiceId = originalInvoiceId;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public void setCustomerId(@NonNull UUID customerId) {
        this.customerId = customerId;
    }

    public BigDecimal getCreditAmount() {
        return creditAmount;
    }

    public void setCreditAmount(@NonNull BigDecimal creditAmount) {
        this.creditAmount = creditAmount;
    }

    public BigDecimal getTaxAmountReversed() {
        return taxAmountReversed;
    }

    public void setTaxAmountReversed(@NonNull BigDecimal taxAmountReversed) {
        this.taxAmountReversed = taxAmountReversed;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(@NonNull BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
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

    public CreditMemoStatus getStatus() {
        return status;
    }

    public void setStatus(@NonNull CreditMemoStatus status) {
        this.status = status;
    }

    public Instant getCreationTimestamp() {
        return creationTimestamp;
    }

    public void setCreationTimestamp(@NonNull Instant creationTimestamp) {
        this.creationTimestamp = creationTimestamp;
    }

    public Instant getPostedTimestamp() {
        return postedTimestamp;
    }

    public void setPostedTimestamp(Instant postedTimestamp) {
        this.postedTimestamp = postedTimestamp;
    }

    public String getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(@NonNull String createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public Boolean getPriorPeriodAdjustment() {
        return priorPeriodAdjustment;
    }

    public void setPriorPeriodAdjustment(@NonNull Boolean priorPeriodAdjustment) {
        this.priorPeriodAdjustment = priorPeriodAdjustment;
    }

    public String getOriginalPeriodId() {
        return originalPeriodId;
    }

    public void setOriginalPeriodId(String originalPeriodId) {
        this.originalPeriodId = originalPeriodId;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(@NonNull String currency) {
        this.currency = currency;
    }

    public BigDecimal getInvoiceBalanceAfter() {
        return invoiceBalanceAfter;
    }

    public void setInvoiceBalanceAfter(BigDecimal invoiceBalanceAfter) {
        this.invoiceBalanceAfter = invoiceBalanceAfter;
    }
}
