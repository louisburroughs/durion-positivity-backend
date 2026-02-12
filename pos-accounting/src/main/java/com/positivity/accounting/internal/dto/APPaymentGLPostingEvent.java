package com.positivity.accounting.internal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Event published when an AP payment succeeds at the gateway and is ready for
 * GL posting.
 * Triggers journal entry creation with:
 * <ul>
 * <li>Dr Accounts Payable (reduces liability for each allocated bill)</li>
 * <li>Cr Cash / Bank (payment outflow)</li>
 * </ul>
 *
 * <p>
 * This event is consumed by the posting engine to create the appropriate
 * journal entries based on active posting rules.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/128">Issue
 *      #128</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "AP Payment GL posting event for journal entry creation")
public class APPaymentGLPostingEvent {

    @NonNull
    @Schema(description = "Event UUID (idempotency key)", example = "01936e5c-1234-7a3d-8b6e-123456789012")
    @JsonProperty("eventId")
    private UUID eventId;

    @NonNull
    @Schema(description = "AP Payment UUID", example = "01936e5b-4567-7a3d-8b6e-1a2345678901")
    @JsonProperty("paymentId")
    private UUID paymentId;

    @NonNull
    @Schema(description = "Idempotency key / payment reference", example = "PAY-2026-00789")
    @JsonProperty("paymentRef")
    private String paymentRef;

    @NonNull
    @Schema(description = "Vendor UUID", example = "01936e5a-7890-7a3d-8b6e-2b3456789012")
    @JsonProperty("vendorId")
    private UUID vendorId;

    @Nullable
    @Schema(description = "Vendor name", example = "Acme Supplies Ltd")
    @JsonProperty("vendorName")
    private String vendorName;

    @NonNull
    @Schema(description = "Gross payment amount", example = "5000.00")
    @JsonProperty("grossAmount")
    private BigDecimal grossAmount;

    @Nullable
    @Schema(description = "Fee amount (e.g., processing fees)", example = "25.00")
    @JsonProperty("feeAmount")
    private BigDecimal feeAmount;

    @Nullable
    @Schema(description = "Net payment amount after fees", example = "4975.00")
    @JsonProperty("netAmount")
    private BigDecimal netAmount;

    @Nullable
    @Schema(description = "Unapplied remainder not allocated to any bill", example = "0.00")
    @JsonProperty("unappliedAmount")
    private BigDecimal unappliedAmount;

    @NonNull
    @Schema(description = "Payment currency (ISO 4217)", example = "USD")
    @JsonProperty("currency")
    private String currency;

    @NonNull
    @Schema(description = "Payment method used", example = "ACH")
    @JsonProperty("paymentMethod")
    private String paymentMethod;

    @Nullable
    @Schema(description = "Gateway transaction ID for reconciliation", example = "txn_abc123xyz")
    @JsonProperty("gatewayTransactionId")
    private String gatewayTransactionId;

    @Nullable
    @Schema(description = "Timestamp of gateway confirmation")
    @JsonProperty("gatewayTimestamp")
    private Instant gatewayTimestamp;

    @Nullable
    @Schema(description = "Optional memo / description", example = "Monthly vendor payment batch")
    @JsonProperty("memo")
    private String memo;

    @NonNull
    @Schema(description = "Bill allocations showing how payment is distributed across vendor bills")
    @JsonProperty("allocations")
    private List<AllocationLine> allocations;

    /**
     * Represents one allocation of this payment against a vendor bill.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Payment allocation to a vendor bill for GL distribution")
    public static class AllocationLine {

        @NonNull
        @Schema(description = "Allocation UUID", example = "01936e5d-abcd-7a3d-8b6e-4d5678901234")
        @JsonProperty("allocationId")
        private UUID allocationId;

        @NonNull
        @Schema(description = "Vendor bill UUID being paid", example = "01936e5e-ef01-7a3d-8b6e-5e6789012345")
        @JsonProperty("vendorBillId")
        private UUID vendorBillId;

        @NonNull
        @Schema(description = "Amount applied to this bill", example = "2500.00")
        @JsonProperty("appliedAmount")
        private BigDecimal appliedAmount;

        @Schema(description = "Allocation sequence order", example = "1")
        @JsonProperty("allocationSequence")
        private int allocationSequence;
    }
}
