package com.positivity.accounting.internal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.positivity.accounting.internal.enums.VendorBillStatus;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Vendor bill response DTO.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Vendor bill details")
public class VendorBillResponse {

    @Schema(description = "Vendor bill UUID", example = "01936e5d-1234-7a3d-8b6e-3c4567890123")
    @JsonProperty("vendorBillId")
    private UUID vendorBillId;

    @Schema(description = "Vendor UUID", example = "01936e5b-4567-7a3d-8b6e-1a2345678901")
    @JsonProperty("vendorId")
    private UUID vendorId;

    @Nullable
    @Schema(description = "Vendor name", example = "Acme Supplies Ltd")
    @JsonProperty("vendorName")
    private String vendorName;

    @Schema(description = "Bill number", example = "BILL-2026-001234")
    @JsonProperty("billNumber")
    private String billNumber;

    @Nullable
    @Schema(description = "Bill date", example = "2026-01-15T00:00:00")
    @JsonProperty("billDate")
    private LocalDateTime billDate;

    @Nullable
    @Schema(description = "Due date", example = "2026-02-14T00:00:00")
    @JsonProperty("dueDate")
    private LocalDateTime dueDate;

    @Schema(description = "Total bill amount", example = "1200.00")
    @JsonProperty("totalAmount")
    private BigDecimal totalAmount;

    @Schema(description = "Bill status", example = "PENDING_RECEIPT_MATCH")
    @JsonProperty("status")
    private VendorBillStatus status;

    @Nullable
    @Schema(description = "Origin event ID (for traceability)", example = "01936e5c-1234-7a3d-8b6e-123456789012")
    @JsonProperty("originEventId")
    private UUID originEventId;

    @Nullable
    @Schema(description = "Origin event type", example = "GOODS_RECEIVED")
    @JsonProperty("originEventType")
    private String originEventType;

    @Nullable
    @Schema(description = "Journal entry ID (if GL posted)", example = "01936e5e-7890-7a3d-8b6e-4d5678901234")
    @JsonProperty("journalEntryId")
    private UUID journalEntryId;

    @Nullable
    @Schema(description = "Payment transaction ID (if paid)", example = "01936e5f-abcd-7a3d-8b6e-5e6789012345")
    @JsonProperty("paymentTransactionId")
    private UUID paymentTransactionId;

    @Schema(description = "Created timestamp", example = "2026-01-15T10:30:00Z")
    @JsonProperty("createdAt")
    private Instant createdAt;

    @Schema(description = "Created by user", example = "system")
    @JsonProperty("createdBy")
    private String createdBy;

    @Nullable
    @Schema(description = "Approval justification (if status = APPROVED)", example = "Approved by AP manager")
    @JsonProperty("approvalJustification")
    private String approvalJustification;

    @Nullable
    @Schema(description = "Rejection reason (if status = REJECTED)", example = "Incorrect invoice amount")
    @JsonProperty("rejectionReason")
    private String rejectionReason;
}
