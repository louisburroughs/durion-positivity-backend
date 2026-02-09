package com.positivity.accounting.internal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.positivity.accounting.internal.enums.APPaymentStatus;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response for an executed AP payment.
 * 
 * <p>
 * Includes payment details, allocations, gateway info, and GL posting status.
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/128">Issue
 *      #128</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "AP Payment response with allocations and GL status")
public class APPaymentResponse {

    @Schema(description = "Payment UUID", example = "01936e5c-7890-7a3d-8b6e-2b3456789012")
    @JsonProperty("paymentId")
    private UUID paymentId;

    @Schema(description = "Payment reference (idempotency key)", example = "01936e5c-7890-7a3d-8b6e-2b3456789012")
    @JsonProperty("paymentRef")
    private String paymentRef;

    @Schema(description = "Vendor UUID", example = "01936e5b-4567-7a3d-8b6e-1a2345678901")
    @JsonProperty("vendorId")
    private UUID vendorId;

    @Nullable
    @Schema(description = "Vendor name", example = "Acme Supplies Ltd")
    @JsonProperty("vendorName")
    private String vendorName;

    @Schema(description = "Gross payment amount", example = "1500.00")
    @JsonProperty("grossAmount")
    private BigDecimal grossAmount;

    @Nullable
    @Schema(description = "Fee amount", example = "15.00")
    @JsonProperty("feeAmount")
    private BigDecimal feeAmount;

    @Nullable
    @Schema(description = "Net amount", example = "1485.00")
    @JsonProperty("netAmount")
    private BigDecimal netAmount;

    @Nullable
    @Schema(description = "Unapplied amount (credit to vendor)", example = "0.00")
    @JsonProperty("unappliedAmount")
    private BigDecimal unappliedAmount;

    @Schema(description = "Currency code", example = "USD")
    @JsonProperty("currency")
    private String currency;

    @Schema(description = "Payment status", example = "GATEWAY_SUCCEEDED")
    @JsonProperty("status")
    private APPaymentStatus status;

    @Nullable
    @Schema(description = "Gateway transaction ID", example = "ch_3QvD2TGxyz123ABC")
    @JsonProperty("gatewayTransactionId")
    private String gatewayTransactionId;

    @Nullable
    @Schema(description = "Gateway response timestamp", example = "2026-02-09T10:30:00Z")
    @JsonProperty("gatewayTimestamp")
    private Instant gatewayTimestamp;

    @Nullable
    @Schema(description = "GL journal entry ID (once posted)", example = "01936e5f-abcd-7a3d-8b6e-5d6789012345")
    @JsonProperty("glJournalEntryId")
    private UUID glJournalEntryId;

    @Nullable
    @Schema(description = "GL posting timestamp", example = "2026-02-09T10:31:00Z")
    @JsonProperty("glPostedAt")
    private Instant glPostedAt;

    @Nullable
    @Schema(description = "GL posting error message (if failed)", example = "Account 2000 not found")
    @JsonProperty("glPostError")
    private String glPostError;

    @Nullable
    @Schema(description = "Payment memo/notes", example = "Monthly supplier payment")
    @JsonProperty("memo")
    private String memo;

    @Schema(description = "Allocations to vendor bills")
    @JsonProperty("allocations")
    private List<AllocationLineResponse> allocations;

    @Schema(description = "Payment created timestamp", example = "2026-02-09T10:29:00Z")
    @JsonProperty("createdAt")
    private Instant createdAt;

    @Schema(description = "User who initiated payment", example = "ap_clerk_1")
    @JsonProperty("createdBy")
    private String createdBy;

    /**
     * Allocation line response for a specific vendor bill.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Allocation of payment to a vendor bill")
    public static class AllocationLineResponse {

        @Schema(description = "Allocation UUID", example = "01936e5e-1234-7a3d-8b6e-4c567890abcd")
        @JsonProperty("allocationId")
        private UUID allocationId;

        @Schema(description = "Vendor bill UUID", example = "01936e5d-1234-7a3d-8b6e-3c4567890123")
        @JsonProperty("vendorBillId")
        private UUID vendorBillId;

        @Schema(description = "Amount applied to this bill", example = "750.00")
        @JsonProperty("appliedAmount")
        private BigDecimal appliedAmount;

        @Schema(description = "Allocation sequence number", example = "1")
        @JsonProperty("allocationSequence")
        private Integer allocationSequence;
    }
}
