package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Request bodies of the vendor-bill approval commands (CAP:550 S12, #2509; SPEC-accounting-workspace §4.3, AW37-AW43).
 * None carries an actor: the actor is always the caller (ADR-0018). A justification, reason or override justification
 * shorter than 10 characters is 400 {@code JUSTIFICATION_REQUIRED}, checked by the service so the code is one.
 */
public final class VendorBillCommands {

    private VendorBillCommands() {}

    @Schema(name = "VendorBillSubmitRequest", description = "Send a bill for approval")
    public record Submit(
            @Schema(
                    description = "Why it goes to approval (at least 10 characters): from PENDING_RECEIPT_MATCH,"
                            + " why it is sent without a delivery match; from MATCH_EXCEPTION, why the exception"
                            + " is resolved",
                    example = "Service bill, no delivery to match",
                    requiredMode = REQUIRED)
            @Size(max = 1000)
            @Nullable
            String justification,

            @Schema(description = "The classification proposed to the approver", requiredMode = NOT_REQUIRED) @Valid
            VendorBillReview.@Nullable Classification classification,

            @Schema(
                    description = "Where the vendor's unreconciled difference posts (AW47); required while"
                            + " gross - (net + tax) exceeds the rounding tolerance",
                    requiredMode = NOT_REQUIRED)
            @Valid
            VendorBillReview.@Nullable Difference difference) {}

    @Schema(name = "VendorBillApproveRequest", description = "Approve a bill; the approval posts it")
    public record Approve(
            @Schema(
                    description = "The approver's justification; optional, at least 10 characters when given",
                    example = "Checked against the delivery note",
                    requiredMode = NOT_REQUIRED)
            @Size(max = 1000)
            @Nullable
            String justification,

            @Schema(
                    description = "How the bill posts (AW39); required for a bill without receipt-matched"
                            + " lines and for non-stock lines, else the one proposed at submission",
                    requiredMode = NOT_REQUIRED)
            @Valid
            VendorBillReview.@Nullable Classification classification,

            @Schema(
                    description = "To post into a CLOSED period: at least 10 characters, with"
                            + " accounting:period:override",
                    example = "Late bill for September, agreed with the accountant",
                    requiredMode = NOT_REQUIRED)
            @Size(max = 1000)
            @Nullable
            String overrideJustification,

            @Schema(
                    description = "Where the vendor's unreconciled difference posts (AW47); required while"
                            + " gross - (net + tax) exceeds the rounding tolerance",
                    requiredMode = NOT_REQUIRED)
            @Valid
            VendorBillReview.@Nullable Difference difference,

            @Schema(
                    description = "Accepts, for this bill only, tax the vendor charged on goods for resale where the"
                            + " tax country's purchase-tax rules hold such bills (check TAX_ON_RESALE_GOODS FAIL): why"
                            + " it is accepted, 10-1000 characters; looked at only when the hold applies (CAP:550 S43)",
                    example = "Vendor resale certificate pending; tax recovered on the next statement",
                    maxLength = 1000,
                    requiredMode = NOT_REQUIRED)
            @Nullable
            String taxOnResaleOverrideJustification) {

        /** An approval without a tax-on-resale override. */
        public Approve(
                @Nullable String justification,
                VendorBillReview.@Nullable Classification classification,
                @Nullable String overrideJustification,
                VendorBillReview.@Nullable Difference difference) {
            this(justification, classification, overrideJustification, difference, null);
        }
    }

    @Schema(name = "VendorBillRejectRequest", description = "Reject a bill awaiting approval")
    public record Reject(
            @Schema(
                    description = "Why (at least 10 characters)",
                    example = "Vendor billed a delivery we refused",
                    requiredMode = REQUIRED)
            @Size(max = 1000)
            @Nullable
            String reason) {}

    @Schema(
            name = "VendorBillVoidRequest",
            description = "Void an approved bill while nothing is allocated to it, or a goods-receipt bill no invoice"
                    + " will match")
    public record VoidBill(
            @Schema(
                    description = "Why (at least 10 characters)",
                    example = "Billed twice, the vendor confirmed",
                    requiredMode = REQUIRED)
            @Size(max = 1000)
            @Nullable
            String reason,

            @Schema(
                    description = "An approved bill only: to reverse into a CLOSED period, at least 10 characters,"
                            + " with accounting:period:override",
                    requiredMode = NOT_REQUIRED)
            @Size(max = 1000)
            @Nullable
            String overrideJustification) {}

    @Schema(name = "VendorBillExceptionResolutionRequest", description = "Resolve a bill in MATCH_EXCEPTION")
    public record ResolveException(
            @Schema(
                    description = "ACCEPT approves and posts the bill, CORRECT sends it back to"
                            + " PENDING_RECEIPT_MATCH, VOID voids it",
                    example = "ACCEPT",
                    allowableValues = {"ACCEPT", "CORRECT", "VOID"},
                    requiredMode = REQUIRED)
            @Nullable
            String resolutionAction,

            @Schema(
                    description =
                            "Why (at least 10 characters); ACCEPT records it as the approval's" + " justification",
                    example = "Price increase agreed by phone",
                    requiredMode = REQUIRED)
            @Size(max = 1000)
            @Nullable
            String reason,

            @Schema(description = "ACCEPT only: how the bill posts (AW39)", requiredMode = NOT_REQUIRED) @Valid
            VendorBillReview.@Nullable Classification classification,

            @Schema(
                    description = "ACCEPT only: to post into a CLOSED period, with accounting:period:override",
                    requiredMode = NOT_REQUIRED)
            @Size(max = 1000)
            @Nullable
            String overrideJustification,

            @Schema(
                    description = "Where the vendor's unreconciled difference posts (AW47), ACCEPT only; required while"
                            + " gross - (net + tax) exceeds the rounding tolerance",
                    requiredMode = NOT_REQUIRED)
            @Valid
            VendorBillReview.@Nullable Difference difference,

            @Schema(
                    description = "ACCEPT only: accepts, for this bill only, tax the vendor charged on goods for"
                            + " resale where the tax country's purchase-tax rules hold such bills, 10-1000 characters"
                            + " (CAP:550 S43)",
                    example = "Vendor resale certificate pending; tax recovered on the next statement",
                    maxLength = 1000,
                    requiredMode = NOT_REQUIRED)
            @Nullable
            String taxOnResaleOverrideJustification) {

        /** A resolution without a tax-on-resale override. */
        public ResolveException(
                @Nullable String resolutionAction,
                @Nullable String reason,
                VendorBillReview.@Nullable Classification classification,
                @Nullable String overrideJustification,
                VendorBillReview.@Nullable Difference difference) {
            this(resolutionAction, reason, classification, overrideJustification, difference, null);
        }
    }

    /**
     * The bill's real due date, entered during approval review (CAP:550 S13, #2510; §4.2, AW11). The actor is the
     * caller; a body field naming one ({@code approvedBy}, {@code operatorId}) is ignored.
     */
    @Schema(name = "VendorBillDueDateRequest", description = "Enter a bill's real due date during approval review")
    public record SetDueDate(
            @Schema(
                    description = "The due date the vendor's document states, YYYY-MM-DD; stored at the start of the"
                            + " day and replacing any estimate",
                    example = "2026-11-07",
                    requiredMode = REQUIRED)
            @Nullable
            LocalDate dueDate,

            @Schema(
                    description = "Why it changes; optional, at least 10 characters when given",
                    example = "Due date read from the paper invoice",
                    requiredMode = NOT_REQUIRED)
            @Size(max = 1000)
            @Nullable
            String justification) {}
}
