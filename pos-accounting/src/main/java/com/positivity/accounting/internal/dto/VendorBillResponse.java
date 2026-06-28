package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Vendor bill response DTO.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Vendor bill details")
public class VendorBillResponse {

    @Schema(description = "Vendor bill UUID", example = "01936e5d-1234-7a3d-8b6e-3c4567890123", requiredMode = REQUIRED)
    @NotNull
    @JsonProperty("vendorBillId")
    private UUID vendorBillId;

    @Schema(description = "Vendor UUID", example = "01936e5b-4567-7a3d-8b6e-1a2345678901", requiredMode = REQUIRED)
    @NotNull
    @JsonProperty("vendorId")
    private UUID vendorId;

    @Nullable
    @Schema(description = "Vendor name", example = "Acme Supplies Ltd", requiredMode = NOT_REQUIRED)
    @JsonProperty("vendorName")
    private String vendorName;

    @Schema(description = "Bill number", example = "BILL-2026-001234", requiredMode = REQUIRED)
    @NotNull
    @JsonProperty("billNumber")
    private String billNumber;

    @Nullable
    @Schema(description = "Bill date", example = "2026-01-15T00:00:00", requiredMode = NOT_REQUIRED)
    @JsonProperty("billDate")
    private LocalDateTime billDate;

    @Nullable
    @Schema(description = "Due date", example = "2026-02-14T00:00:00", requiredMode = NOT_REQUIRED)
    @JsonProperty("dueDate")
    private LocalDateTime dueDate;

    @Schema(description = "Total bill amount", example = "1200.00", requiredMode = REQUIRED)
    @NotNull
    @JsonProperty("totalAmount")
    private BigDecimal totalAmount;

    @Schema(description = "Bill status", example = "PENDING_RECEIPT_MATCH", requiredMode = REQUIRED)
    @NotNull
    @JsonProperty("status")
    private VendorBillStatus status;

    @Nullable
    @Schema(
            description = "Origin event ID (for traceability)",
            example = "01936e5c-1234-7a3d-8b6e-123456789012",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("originEventId")
    private UUID originEventId;

    @Nullable
    @Schema(description = "Origin event type", example = "GOODS_RECEIVED", requiredMode = NOT_REQUIRED)
    @JsonProperty("originEventType")
    private String originEventType;

    @Nullable
    @Schema(
            description = "Journal entry ID (if GL posted)",
            example = "01936e5e-7890-7a3d-8b6e-4d5678901234",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("journalEntryId")
    private UUID journalEntryId;

    @Nullable
    @Schema(
            description = "Payment transaction ID (if paid)",
            example = "01936e5f-abcd-7a3d-8b6e-5e6789012345",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("paymentTransactionId")
    private UUID paymentTransactionId;

    @Schema(description = "Created timestamp", example = "2026-01-15T10:30:00Z", requiredMode = REQUIRED)
    @NotNull
    @JsonProperty("createdAt")
    private Instant createdAt;

    @Schema(description = "Created by user", example = "system", requiredMode = NOT_REQUIRED)
    @JsonProperty("createdBy")
    private String createdBy;

    @Nullable
    @Schema(
            description = "Approval justification (if status = APPROVED)",
            example = "Approved by AP manager",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("approvalJustification")
    private String approvalJustification;

    @Nullable
    @Schema(
            description = "Rejection reason (if status = REJECTED)",
            example = "Incorrect invoice amount",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("rejectionReason")
    private String rejectionReason;
}
