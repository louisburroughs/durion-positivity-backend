package com.positivity.accounting.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.positivity.accounting.internal.enums.APPaymentSettlementStatus;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

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
    @Schema(description = "Unapplied amount (credit to vendor)", example = "0.00")
    @JsonProperty("unappliedAmount")
    private BigDecimal unappliedAmount;

    @Schema(description = "Currency code", example = "USD")
    @JsonProperty("currency")
    private String currency;

    @Nullable
    @Schema(
            description = "GL account id of the BANK_CASH account the payment was made from (CAP:550 S42)",
            example = "01936e5c-1111-7a3d-8b6e-2b3456789012")
    @JsonProperty("bankAccountId")
    private UUID bankAccountId;

    @Schema(description = "How the vendor was paid; CASH is a drawer cash-on-delivery payment (S17)", example = "ACH")
    private com.positivity.accounting.internal.enums.PaymentMethod paymentMethod;

    @Schema(description = "For a CASH payment: the register session whose close created it (S17)")
    private UUID registerSessionId;

    @Schema(description = "For a CASH payment: the VENDOR_COD drawer movement it came from (S17)")
    private UUID cashMovementId;

    @Nullable
    @Schema(
            description =
                    "The tenant business date the payment executed on; its entry posts on this date (CAP:550 S42)",
            example = "2026-10-08")
    @JsonProperty("paymentDate")
    private LocalDate paymentDate;

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
    @Schema(
            description = "Why the posting was refused, as its code (GL_MAPPING_NOT_CONFIGURED, PERIOD_CLOSED,"
                    + " PERIOD_HARD_LOCKED, ACCOUNTING_TIME_ZONE_UNSET, GL_ACCOUNT_NOT_ACTIVE) or GL_POST_RETRIES_EXHAUSTED"
                    + " (the outbox gave up on a transient failure) while the payment is GL_POST_FAILED; in every case"
                    + " gl-posting-retry is the remedy once the cause is fixed",
            example = "GL_MAPPING_NOT_CONFIGURED")
    @JsonProperty("glPostError")
    private String glPostError;

    @Schema(
            description = "DEFERRED_PAST_HARD_LOCK when the entry was dated the retry's business date because the"
                    + " payment's own date is hard-locked (#2729); null otherwise",
            example = "DEFERRED_PAST_HARD_LOCK",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String postingDateRule;

    @Schema(description = "Why a VOIDED payment was voided (#2729)", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String voidReason;

    @Schema(description = "When the payment was voided", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant voidedAt;

    @Schema(description = "Who voided the payment", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String voidedBy;

    @Schema(
            description = "Whether the gateway settled the charge (#2642): IN_FLIGHT (accepted PENDING, re-read by the"
                    + " settlement job), SETTLED, RETURNED; absent on CASH payments and rows older than the field",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private APPaymentSettlementStatus settlementStatus;

    @Schema(description = "When the settlement job last re-read the gateway", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant settlementCheckedAt;

    @Schema(description = "When the gateway answered SUCCEEDED", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant settledAt;

    @Schema(description = "When the return was recorded", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Instant returnedAt;

    @Schema(
            description = "The date the mirror entry is (or will be) posted on",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private LocalDate returnDate;

    @Schema(
            description = "RETURN_DATE (the failure date's period was open) or DETECTION_DATE (it was closed, so the"
                    + " mirror is dated when the return was detected)",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String returnDateRule;

    @Schema(description = "The gateway's or bank's return code (never free text)", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String returnFailureCode;

    @Schema(
            description = "The mirror entry: the reversal of the payment's own journal entry",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private UUID returnJournalEntryId;

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

        @Schema(
                description = "Set when the payment was returned (#2642): the row is history and no longer counts"
                        + " towards the bill's allocated amount",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        private Instant reopenedAt;

        @Schema(description = "Allocation sequence number", example = "1")
        @JsonProperty("allocationSequence")
        private Integer allocationSequence;
    }
}
