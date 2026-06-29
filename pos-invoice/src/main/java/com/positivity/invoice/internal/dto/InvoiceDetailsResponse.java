package com.positivity.invoice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.invoice.internal.enums.InvoiceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(description = "Full details of an invoice including line items and adjustments")
public class InvoiceDetailsResponse {

    @Schema(
            description = "Unique identifier of the invoice",
            example = "01960003-0000-7000-8000-000000000040",
            requiredMode = NOT_REQUIRED)
    private UUID invoiceId;

    @Schema(description = "Human-readable invoice number", example = "INV-2026-000123", requiredMode = NOT_REQUIRED)
    private String invoiceNumber;

    @Schema(
            description = "Identifier of the associated workorder",
            example = "01960003-0000-7000-8000-000000000041",
            requiredMode = NOT_REQUIRED)
    private UUID workorderId;

    @Schema(
            description = "Identifier of the source estimate",
            example = "01960003-0000-7000-8000-000000000042",
            requiredMode = NOT_REQUIRED)
    private UUID estimateId;

    @Schema(
            description = "Identifier of the approval record",
            example = "01960003-0000-7000-8000-000000000043",
            requiredMode = NOT_REQUIRED)
    private UUID approvalId;

    @Schema(
            description = "Identifier of the billed party",
            example = "01960003-0000-7000-8000-000000000044",
            requiredMode = NOT_REQUIRED)
    private String partyId;

    @Schema(description = "Current lifecycle status of the invoice", example = "FINALIZED", requiredMode = NOT_REQUIRED)
    private InvoiceStatus status;

    @Schema(
            description = "Sum of line items before tax and adjustments",
            example = "200.00",
            requiredMode = NOT_REQUIRED)
    private BigDecimal subtotal;

    @Schema(description = "Total tax applied to the invoice", example = "16.00", requiredMode = NOT_REQUIRED)
    private BigDecimal tax;

    @Schema(description = "Grand total payable on the invoice", example = "191.00", requiredMode = NOT_REQUIRED)
    private BigDecimal total;

    @Schema(description = "Net total of all monetary adjustments", example = "-25.00", requiredMode = NOT_REQUIRED)
    private BigDecimal adjustments;

    @Schema(
            description = "Timestamp when the invoice was created",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "Timestamp when the invoice was last updated",
            example = "2026-01-16T11:45:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant updatedAt;

    @Schema(
            description = "Timestamp when the invoice was finalized",
            example = "2026-01-16T12:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant finalizedAt;

    @Schema(
            description = "Identifier of the actor who finalized the invoice",
            example = "jdoe",
            requiredMode = NOT_REQUIRED)
    private String finalizedBy;

    @Schema(
            description = "Timestamp when the invoice was reverted to draft",
            example = "2026-01-16T13:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant revertedAt;

    @Schema(
            description = "Reason the invoice was reverted",
            example = "Incorrect line item billed",
            requiredMode = NOT_REQUIRED)
    private String reversionReason;

    @Schema(
            description = "Identifier of the actor who reverted the invoice",
            example = "msmith",
            requiredMode = NOT_REQUIRED)
    private String revertedBy;

    @Valid
    @Schema(description = "Line items belonging to the invoice", requiredMode = REQUIRED)
    private List<InvoiceItemResponse> items = new ArrayList<>();

    @Valid
    @Schema(description = "Monetary adjustment entries applied to the invoice", requiredMode = REQUIRED)
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

    @Nullable
    public String getRevertedBy() {
        return revertedBy;
    }

    public void setRevertedBy(@Nullable String revertedBy) {
        this.revertedBy = revertedBy;
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
