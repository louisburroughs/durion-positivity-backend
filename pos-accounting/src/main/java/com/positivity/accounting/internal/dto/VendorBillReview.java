package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.MatchConfidence;
import com.positivity.accounting.internal.enums.VendorBillAction;
import com.positivity.accounting.internal.enums.VendorBillCheckOutcome;
import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import com.positivity.accounting.internal.enums.VendorBillPostingDateRule;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The blocks the vendor-bill read gains for the review screen (CAP:550 S12, #2509; SPEC-accounting-workspace §5.2,
 * P5, P8). Every block carrying money states its ISO 4217 {@code currencyCode} (ADR-0067 R-1).
 */
public final class VendorBillReview {

    private VendorBillReview() {}

    /** Where the bill came from, taken from its origin event type. */
    @Schema(name = "VendorBillChannel", description = "Where the bill came from")
    public enum Channel {
        /** Created from a goods receipt ({@code GOODS_RECEIVED}). */
        GOODS_RECEIPT,
        /** Created from a supplier connection's invoice ({@code SUPPLIER_INVOICE_RECEIVED}, EDI). */
        SUPPLIER_CONNECTION
    }

    /** The approval tier a decision needs; derived, never stored. Always OVER_LIMIT until S13's limits exist. */
    @Schema(name = "VendorBillApprovalTier", description = "The approval tier the bill needs")
    public enum RequiredTier {
        CLERK,
        OVER_LIMIT
    }

    /** The approver's classification of the bill (AW39). */
    @Schema(name = "VendorBillClassification", description = "How the bill posts at approval (AW39)")
    public record Classification(
            @Schema(
                    description = "GOODS or EXPENSE for a bill without receipt-matched lines (EDI bills);"
                            + " EXPENSE or PRICE_ALLOWANCE for a credit note. Lines of a goods-receipt bill"
                            + " class themselves",
                    example = "EXPENSE",
                    requiredMode = NOT_REQUIRED)
            @Nullable
            VendorBillDebitClass debitClass,

            @Schema(
                    description = "The VENDOR_BILL expense key, EXPENSE_<CODE>: required with EXPENSE and for a"
                            + " bill with non-stock lines",
                    example = "EXPENSE_SHOP_SUPPLIES",
                    requiredMode = NOT_REQUIRED)
            @Nullable
            String expenseMappingKey) {}

    @Schema(
            name = "VendorBillApproval",
            description = "Who sent the bill for approval and, once approved, who approved it")
    public record Approval(
            @Schema(description = "When the bill was sent for approval", requiredMode = NOT_REQUIRED) @Nullable
            Instant submittedAt,

            @Schema(
                    description = "Who sent it: the person, or SYSTEM for a HIGH match",
                    example = "clerk.ana",
                    requiredMode = NOT_REQUIRED)
            @Nullable
            String submittedBy,

            @Schema(description = "Why it was sent", requiredMode = NOT_REQUIRED) @Nullable
            String submissionJustification,

            @Schema(description = "The tier the approval needs", example = "OVER_LIMIT", requiredMode = REQUIRED)
            RequiredTier requiredTier,

            @Schema(description = "The classification proposed at submission", requiredMode = NOT_REQUIRED) @Nullable
            Classification proposedClassification,

            @Schema(description = "When it was approved; only on an approved bill", requiredMode = NOT_REQUIRED)
            @Nullable
            Instant approvedAt,

            @Schema(description = "Who approved it; only on an approved bill", requiredMode = NOT_REQUIRED) @Nullable
            String approvedBy,

            @Schema(description = "The approver's justification; only on an approved bill", requiredMode = NOT_REQUIRED)
            @Nullable
            String approvalJustification) {}

    @Schema(name = "VendorBillRejection", description = "Who rejected or voided the bill, and why")
    public record Rejection(
            @Schema(description = "When", requiredMode = REQUIRED)
            Instant rejectedAt,

            @Schema(description = "Who", example = "controller.cfo", requiredMode = REQUIRED)
            String rejectedBy,

            @Schema(description = "The reason given", requiredMode = REQUIRED)
            String reason) {}

    @Schema(name = "VendorBillMatchPoints", description = "Points per match criterion (P3)")
    public record Points(
            @Schema(description = "Amount, of 40", example = "40", requiredMode = REQUIRED)
            int amount,

            @Schema(description = "Products, of 30", example = "30", requiredMode = REQUIRED)
            int products,

            @Schema(description = "Date, of 20", example = "20", requiredMode = REQUIRED)
            int date,

            @Schema(description = "Purchase order, of 5", example = "5", requiredMode = REQUIRED)
            int purchaseOrder) {}

    @Schema(name = "VendorBillMatchCandidateSummary", description = "One unresolved candidate of an ambiguous match")
    public record Candidate(
            @Schema(description = "Candidate id, for the select command", requiredMode = REQUIRED)
            UUID candidateId,

            @Schema(description = "The candidate bill", requiredMode = REQUIRED)
            UUID vendorBillId,

            @Schema(
                    description = "The candidate bill's number",
                    example = "BILL_A1B2C3D4_20261003_0000012",
                    requiredMode = NOT_REQUIRED)
            @Nullable
            String billNumber,

            @Schema(description = "The candidate bill's total", example = "400.00", requiredMode = NOT_REQUIRED)
            @Nullable
            BigDecimal billTotal,

            @Schema(description = "ISO 4217 code of billTotal", example = "USD", requiredMode = REQUIRED)
            String currencyCode,

            @Schema(description = "Total score", example = "65", requiredMode = REQUIRED)
            int score,

            @Schema(
                    description = "Points per criterion; null on candidates scored before #2509",
                    requiredMode = NOT_REQUIRED)
            @Nullable
            Points points) {}

    @Schema(name = "VendorBillMatch", description = "The bill's latest match evidence and the open candidates")
    public record Match(
            @Schema(description = "Evidence record id", requiredMode = REQUIRED)
            UUID evidenceId,

            @Schema(description = "MATCH or CANDIDATE_SELECTION", example = "MATCH", requiredMode = REQUIRED)
            String source,

            @Schema(description = "Confidence of the match", example = "HIGH_CONFIDENCE", requiredMode = REQUIRED)
            MatchConfidence confidence,

            @Schema(description = "Total score", example = "95", requiredMode = REQUIRED)
            int score,

            @Schema(description = "Points per criterion", requiredMode = REQUIRED)
            Points points,

            @Schema(description = "The vendor's invoice number", example = "INV-88421", requiredMode = REQUIRED)
            String invoiceReference,

            @Schema(description = "The invoice date", requiredMode = REQUIRED)
            LocalDateTime invoiceDate,

            @Schema(description = "What the receipt put on the bill", example = "400.00", requiredMode = REQUIRED)
            BigDecimal receivedTotal,

            @Schema(description = "What the vendor billed", example = "412.00", requiredMode = REQUIRED)
            BigDecimal billedTotal,

            @Schema(description = "ISO 4217 code of both totals", example = "USD", requiredMode = REQUIRED)
            String currencyCode,

            @Schema(description = "Whether every line and the total were within tolerance", requiredMode = REQUIRED)
            boolean withinTolerance,

            @Schema(description = "When the evidence was recorded", requiredMode = REQUIRED)
            Instant recordedAt,

            @Schema(
                    description = "Unresolved candidates of an ambiguous match naming this bill",
                    requiredMode = REQUIRED)
            List<Candidate> candidates) {}

    @Schema(name = "VendorBillLine", description = "A received line with what the vendor billed for it")
    public record Line(
            @Schema(description = "Line number", example = "1", requiredMode = REQUIRED)
            int lineNumber,

            @Schema(description = "Product", requiredMode = REQUIRED)
            UUID productId,

            @Schema(description = "Description", example = "Brake pads", requiredMode = NOT_REQUIRED) @Nullable
            String description,

            @Schema(description = "Whether the product is stocked", requiredMode = REQUIRED)
            boolean inventoryItem,

            @Schema(
                    description = "Quantity received (0 for a billed line with no receipt)",
                    example = "4",
                    requiredMode = REQUIRED)
            BigDecimal receivedQuantity,

            @Schema(description = "Unit price received", example = "100.00", requiredMode = REQUIRED)
            BigDecimal receivedUnitPrice,

            @Schema(
                    description = "Quantity billed; null until the bill is matched",
                    example = "4",
                    requiredMode = NOT_REQUIRED)
            @Nullable
            BigDecimal billedQuantity,

            @Schema(
                    description = "Unit price billed; null until the bill is matched",
                    example = "103.00",
                    requiredMode = NOT_REQUIRED)
            @Nullable
            BigDecimal billedUnitPrice,

            @Schema(description = "ISO 4217 code of the prices", example = "USD", requiredMode = REQUIRED)
            String currencyCode) {}

    @Schema(name = "VendorBillCheck", description = "One check the review shows")
    public record Check(
            @Schema(
                    description = "MATCHED_TO_DELIVERY or WITHIN_PRICE_TOLERANCE",
                    example = "MATCHED_TO_DELIVERY",
                    requiredMode = REQUIRED)
            String code,

            @Schema(description = "PASS, FAIL or NOT_APPLICABLE", example = "PASS", requiredMode = REQUIRED)
            VendorBillCheckOutcome outcome,

            @Schema(description = "Values the message is built from", requiredMode = REQUIRED)
            Map<String, String> args) {}

    @Schema(name = "VendorBillAvailableAction", description = "A decision the caller may take on the bill now")
    public record AvailableAction(
            @Schema(description = "The decision", example = "APPROVE", requiredMode = REQUIRED)
            VendorBillAction action,

            @Schema(description = "Whether it may be taken now", requiredMode = REQUIRED)
            boolean allowed,

            @Schema(description = "Why not; reserved for S13's rule-based blocks", requiredMode = NOT_REQUIRED)
            @Nullable
            String blockedReason,

            @Schema(
                    description = "Whether the command needs a justification of at least 10 characters",
                    requiredMode = REQUIRED)
            boolean justificationRequired) {}

    @Schema(name = "VendorBillPosting", description = "The bill's entry at approval and, after a void, its reversal")
    public record Posting(
            @Schema(description = "The entry's id", requiredMode = REQUIRED)
            UUID journalEntryId,

            @Schema(description = "The entry's number", example = "JE-202610-000123", requiredMode = NOT_REQUIRED)
            @Nullable
            String journalEntryReference,

            @Schema(description = "The entry's date", requiredMode = REQUIRED)
            LocalDate postingDate,

            @Schema(description = "Why it carries that date", example = "BILL_DATE", requiredMode = REQUIRED)
            VendorBillPostingDateRule postingDateRule,

            @Schema(
                    description = "The billed gross credited to accounts payable",
                    example = "412.00",
                    requiredMode = REQUIRED)
            BigDecimal grossAmount,

            @Schema(description = "ISO 4217 code of grossAmount", example = "USD", requiredMode = REQUIRED)
            String currencyCode,

            @Schema(
                    description = "The reversal entry's number after a void",
                    example = "JE-202610-000140",
                    requiredMode = NOT_REQUIRED)
            @Nullable
            String reversalReference,

            @Schema(description = "The void date, the reversal's date", requiredMode = NOT_REQUIRED) @Nullable
            LocalDate reversalDate) {}

    @Schema(name = "VendorBillStageCounts", description = "How many bills are in each stage of Bills to pay")
    public record StageCounts(
            @Schema(
                    description = "PENDING_RECEIPT_MATCH, MATCH_EXCEPTION and CURRENCY_HOLD",
                    example = "4",
                    requiredMode = REQUIRED)
            long check,

            @Schema(description = "AWAITING_APPROVAL", example = "2", requiredMode = REQUIRED)
            long approve,

            @Schema(description = "APPROVED with an open amount above 0", example = "7", requiredMode = REQUIRED)
            long pay,

            @Schema(
                    description = "APPROVED, paid in full, last payment this month",
                    example = "3",
                    requiredMode = REQUIRED)
            long done,

            @Schema(description = "When the counts were taken", requiredMode = REQUIRED)
            Instant asOf) {}

    @Schema(name = "VendorBillStageRow", description = "One bill in a stage list")
    public record StageRow(
            @Schema(description = "Bill id", requiredMode = REQUIRED)
            UUID vendorBillId,

            @Schema(description = "Bill number", example = "INV-88421", requiredMode = REQUIRED)
            String billNumber,

            @Schema(description = "Vendor name", example = "Acme Parts Co", requiredMode = NOT_REQUIRED) @Nullable
            String vendorName,

            @Schema(description = "Total", example = "412.00", requiredMode = REQUIRED)
            BigDecimal totalAmount,

            @Schema(description = "ISO 4217 code of the amounts", example = "USD", requiredMode = REQUIRED)
            String currencyCode,

            @Schema(description = "Bill date", requiredMode = REQUIRED)
            LocalDateTime billDate,

            @Schema(description = "Due date; null when the bill states none", requiredMode = NOT_REQUIRED) @Nullable
            LocalDateTime dueDate,

            @Schema(description = "Status", example = "AWAITING_APPROVAL", requiredMode = REQUIRED)
            com.positivity.accounting.internal.enums.VendorBillStatus status,

            @Schema(description = "Where the bill came from", requiredMode = NOT_REQUIRED) @Nullable
            Channel channel,

            @Schema(description = "When it was sent for approval", requiredMode = NOT_REQUIRED) @Nullable
            Instant submittedAt,

            @Schema(description = "Total less allocated payments", example = "412.00", requiredMode = REQUIRED)
            BigDecimal openAmount) {}
}
