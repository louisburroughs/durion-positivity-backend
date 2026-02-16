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
    private String customerId;
    private InvoiceStatus status;
    private BigDecimal subtotal;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private Instant createdAt;
    private Instant finalizedAt;
    private String finalizedBy;
    private List<InvoiceItemResponse> items = new ArrayList<>();
    private List<InvoiceAdjustmentResponse> adjustments = new ArrayList<>();

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
    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(@Nullable String customerId) {
        this.customerId = customerId;
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
    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public void setTaxAmount(@Nullable BigDecimal taxAmount) {
        this.taxAmount = taxAmount;
    }

    @Nullable
    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(@Nullable BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    @Nullable
    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(@Nullable Instant createdAt) {
        this.createdAt = createdAt;
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

    public List<InvoiceItemResponse> getItems() {
        return items;
    }

    public void setItems(List<InvoiceItemResponse> items) {
        this.items = items;
    }

    public List<InvoiceAdjustmentResponse> getAdjustments() {
        return adjustments;
    }

    public void setAdjustments(List<InvoiceAdjustmentResponse> adjustments) {
        this.adjustments = adjustments;
    }
}
