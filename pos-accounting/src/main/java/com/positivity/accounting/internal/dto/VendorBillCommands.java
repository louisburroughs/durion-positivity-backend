package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
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
            VendorBillReview.@Nullable Classification classification) {}

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
            String overrideJustification) {}

    @Schema(name = "VendorBillRejectRequest", description = "Reject a bill awaiting approval")
    public record Reject(
            @Schema(
                    description = "Why (at least 10 characters)",
                    example = "Vendor billed a delivery we refused",
                    requiredMode = REQUIRED)
            @Size(max = 1000)
            @Nullable
            String reason) {}

    @Schema(name = "VendorBillVoidRequest", description = "Void an approved bill while nothing is allocated to it")
    public record VoidApproved(
            @Schema(
                    description = "Why (at least 10 characters)",
                    example = "Billed twice, the vendor confirmed",
                    requiredMode = REQUIRED)
            @Size(max = 1000)
            @Nullable
            String reason,

            @Schema(
                    description = "To reverse into a CLOSED period: at least 10 characters, with"
                            + " accounting:period:override",
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
            String overrideJustification) {}
}
