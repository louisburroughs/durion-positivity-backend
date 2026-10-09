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
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Vendor bill response DTO: the bill, and for the review screen (#2509) its approval, rejection, match evidence,
 * lines, checks, the caller's available actions and its posting.
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

    @Nullable
    @Schema(
            description = "ISO 4217 currency the bill is stated in; null means the ledger currency (a bill "
                    + "recorded before currencies were kept). A bill in another currency is held in CURRENCY_HOLD",
            example = "USD",
            requiredMode = NOT_REQUIRED,
            nullable = true)
    @JsonProperty("currency")
    private String currency;

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

    @Schema(description = "Where the bill came from", example = "SUPPLIER_CONNECTION", requiredMode = NOT_REQUIRED)
    @JsonProperty("channel")
    private VendorBillReview.@Nullable Channel channel;

    @Schema(
            description = "The submission and, on an approved bill, the approval; null before the bill is sent",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("approval")
    private VendorBillReview.@Nullable Approval approval;

    @Schema(description = "Who rejected or voided the bill; only for REJECTED and VOIDED", requiredMode = NOT_REQUIRED)
    @JsonProperty("rejection")
    private VendorBillReview.@Nullable Rejection rejection;

    @Nullable
    @Schema(
            description = "Why the bill is held: the MATCH_EXCEPTION or CURRENCY_HOLD explanation; null in any other"
                    + " status",
            example = "Quantity or price mismatch detected during three-way match",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("statusExplanation")
    private String statusExplanation;

    @Schema(description = "Total less allocated payments", example = "412.00", requiredMode = REQUIRED)
    @NotNull
    @JsonProperty("openAmount")
    private BigDecimal openAmount;

    @Schema(description = "The latest match evidence; null before any match", requiredMode = NOT_REQUIRED)
    @JsonProperty("match")
    private VendorBillReview.@Nullable Match match;

    @Schema(
            description = "Unresolved candidates of every ambiguous match naming this bill, each with its"
                    + " invoiceEventId; pick one with the select command before sending or accepting the bill",
            requiredMode = REQUIRED)
    @NotNull
    @JsonProperty("openCandidates")
    private List<VendorBillReview.Candidate> openCandidates;

    @Schema(
            description = "Re-issues of this approved bill under its number, held as exception items",
            requiredMode = REQUIRED)
    @NotNull
    @JsonProperty("reissues")
    private List<VendorBillReview.Reissue> reissues;

    @Nullable
    @Schema(
            description = "The net the vendor's document states (AW47); null on a bill without header totals",
            example = "1000.00",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("netAmount")
    private BigDecimal netAmount;

    @Nullable
    @Schema(
            description = "The tax the vendor's document states, never recalculated (AW39, AW47)",
            example = "70.00",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("taxAmount")
    private BigDecimal taxAmount;

    @Schema(
            description = "Received lines with what the vendor billed; empty for a bill without lines",
            requiredMode = REQUIRED)
    @NotNull
    @JsonProperty("lines")
    private List<VendorBillReview.Line> lines;

    @Schema(
            description = "MATCHED_TO_DELIVERY, WITHIN_PRICE_TOLERANCE, TOTALS_ADD_UP and, on an EDI bill classified"
                    + " GOODS, OPEN_DELIVERIES_FROM_VENDOR",
            requiredMode = REQUIRED)
    @NotNull
    @JsonProperty("checks")
    private List<VendorBillReview.Check> checks;

    @Schema(
            description = "The decisions valid for the bill's status that the caller holds a permission for (P5)",
            requiredMode = REQUIRED)
    @NotNull
    @JsonProperty("availableActions")
    private List<VendorBillReview.AvailableAction> availableActions;

    @Schema(description = "The entry posted at approval; null until approved", requiredMode = NOT_REQUIRED)
    @JsonProperty("posting")
    private VendorBillReview.@Nullable Posting posting;

    @Schema(
            description = "The tax the vendor's document states, by tax type (CAP:550 S32d); empty when it states"
                    + " none by type",
            requiredMode = REQUIRED)
    @JsonProperty("taxByType")
    private List<VendorBillReview.TaxByType> taxByType;

    @Schema(
            description = "What the posting did with each stated tax amount for a tenant that recovers input tax:"
                    + " the amount recovered and its account, or why nothing was (CAP:550 S32d); null before the"
                    + " posting and for a tenant without recovery, whose bill books the gross",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("inputTaxRecovery")
    private @Nullable List<VendorBillReview.InputTaxRecovery> inputTaxRecovery;

    @Schema(
            description = "What let the bill through its tax country's hold for tax on goods for resale at approval"
                    + " (CAP:550 S43); null when the hold did not apply",
            requiredMode = NOT_REQUIRED)
    @JsonProperty("taxOnResaleOverride")
    private VendorBillReview.@Nullable TaxOnResaleOverride taxOnResaleOverride;
}
