package com.positivity.invoice.internal.dto;

import com.positivity.invoice.internal.enums.InvoiceStatus;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class InvoiceDetailsResponse {

    private UUID invoiceId;
    private String invoiceNumber;
    private UUID workorderId;
    private UUID estimateId;
    private UUID approvalId;
    private String partyId;
    private InvoiceStatus status;
    private BigDecimal subtotal;
    private BigDecimal tax;
    private BigDecimal total;
    private BigDecimal adjustments;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant finalizedAt;
    private String finalizedBy;
    private Instant revertedAt;
    private String reversionReason;
    private List<InvoiceItemResponse> items = new ArrayList<>();
    private List<InvoiceAdjustmentResponse> adjustmentEntries = new ArrayList<>();

    @Nullable
    public UUID getInvoiceId() {
        return invoiceId;
    }

    public void setInvoiceId(@Nullable UUID invoiceId) {
        this.invoiceId = invoiceId;
    }

    @Nullable
    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public void setInvoiceNumber(@Nullable String invoiceNumber) {
        this.invoiceNumber = invoiceNumber;
    }

    @Nullable
    public UUID getWorkorderId() {
        return workorderId;
    }

    public void setWorkorderId(@Nullable UUID workorderId) {
        this.workorderId = workorderId;
    }

    @Nullable
    public UUID getEstimateId() {
        return estimateId;
    }

    public void setEstimateId(@Nullable UUID estimateId) {
        this.estimateId = estimateId;
    }

    @Nullable
    public UUID getApprovalId() {
        return approvalId;
    }

    public void setApprovalId(@Nullable UUID approvalId) {
        this.approvalId = approvalId;
    }

    @Nullable
    public String getPartyId() {
        return partyId;
    }

    public void setPartyId(@Nullable String partyId) {
        this.partyId = partyId;
    }

    @Nullable
    public InvoiceStatus getStatus() {
        return status;
    }

    public void setStatus(@Nullable InvoiceStatus status) {
        this.status = status;
    }

    @Nullable
    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(@Nullable BigDecimal subtotal) {
        this.subtotal = subtotal;
    }

    @Nullable
    public BigDecimal getTax() {
        return tax;
    }

    public void setTax(@Nullable BigDecimal tax) {
        this.tax = tax;
    }

    @Nullable
    public BigDecimal getTotal() {
        return total;
    }

    public void setTotal(@Nullable BigDecimal total) {
        this.total = total;
    }

    @Nullable
    public BigDecimal getAdjustments() {
        return adjustments;
    }

    public void setAdjustments(@Nullable BigDecimal adjustments) {
        this.adjustments = adjustments;
    }

    @Nullable
    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(@Nullable Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Nullable
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(@Nullable Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Nullable
    public Instant getFinalizedAt() {
        return finalizedAt;
    }

    public void setFinalizedAt(@Nullable Instant finalizedAt) {
        this.finalizedAt = finalizedAt;
    }

    @Nullable
    public String getFinalizedBy() {
        return finalizedBy;
    }

    public void setFinalizedBy(@Nullable String finalizedBy) {
        this.finalizedBy = finalizedBy;
    }

    @Nullable
    public Instant getRevertedAt() {
        return revertedAt;
    }

    public void setRevertedAt(@Nullable Instant revertedAt) {
        this.revertedAt = revertedAt;
    }

    @Nullable
    public String getReversionReason() {
        return reversionReason;
    }

    public void setReversionReason(@Nullable String reversionReason) {
        this.reversionReason = reversionReason;
    }

    public List<InvoiceItemResponse> getItems() {
        return items;
    }

    public void setItems(List<InvoiceItemResponse> items) {
        this.items = items;
    }

    public List<InvoiceAdjustmentResponse> getAdjustmentEntries() {
        return adjustmentEntries;
    }

    public void setAdjustmentEntries(List<InvoiceAdjustmentResponse> adjustments) {
        this.adjustmentEntries = adjustments;
    }

}
