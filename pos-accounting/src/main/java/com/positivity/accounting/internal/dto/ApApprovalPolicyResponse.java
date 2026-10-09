package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The tenant's effective AP approval policy and its change history (CAP:550 S13, #2510; SPEC-accounting-workspace
 * §5.5). A setting never written reads as its default.
 */
@Schema(name = "ApApprovalPolicyResponse", description = "The effective AP approval policy and its change history")
public record ApApprovalPolicyResponse(
        @Schema(
                description = "How much a clerk may approve (absolute total including tax); 0.00 means no clerk"
                        + " approves",
                example = "2500.00",
                requiredMode = REQUIRED)
        BigDecimal clerkApprovalLimit,

        @Schema(
                description = "Up to how much the system approves a strong goods-receipt match, never above the clerk"
                        + " limit; 0.00 means off",
                example = "500.00",
                requiredMode = REQUIRED)
        BigDecimal autoApprovalLimit,

        @Schema(description = "ISO 4217 functional currency of both limits", example = "USD", requiredMode = REQUIRED)
        String currencyCode,

        @Schema(description = "Whether a bill's creator may approve it, with a justification", requiredMode = REQUIRED)
        boolean allowCreatorApproval,

        @Schema(description = "Whether a bill's approver may pay it", requiredMode = REQUIRED)
        boolean allowApproverPayment,

        @Schema(description = "The default AP terms", example = "NET30", requiredMode = REQUIRED)
        String defaultTerms,

        @Schema(description = "When these values were read", requiredMode = REQUIRED)
        Instant asOf,

        @ArraySchema(
                arraySchema =
                        @Schema(
                                description = "The changes, newest first, one row per setting changed; one page",
                                requiredMode = REQUIRED))
        List<HistoryRow> history,

        @Schema(description = "The history page returned, from 0", example = "0", requiredMode = REQUIRED)
        int historyPage,

        @Schema(description = "The history page size", example = "20", requiredMode = REQUIRED)
        int historySize,

        @Schema(description = "How many history rows there are in all", example = "3", requiredMode = REQUIRED)
        long historyTotal) {

    /** One change of one setting. */
    @Schema(name = "ApApprovalPolicyHistoryRow", description = "One change of one AP approval policy setting")
    public record HistoryRow(
            @Schema(description = "When it changed", requiredMode = REQUIRED)
            Instant changedAt,

            @Schema(
                    description = "Who changed it: the sign-in name, kept for audit and never shown to a person",
                    example = "controller.cfo",
                    requiredMode = REQUIRED)
            String changedBy,

            @Schema(
                    description = "The display name of the person who changed it (\"First Last\"), resolved now from"
                            + " accounting's people-contact copy; null when not known, never the sign-in name",
                    example = "Dana Reyes",
                    requiredMode = NOT_REQUIRED)
            @Nullable
            String changedByName,

            @ArraySchema(
                    arraySchema =
                            @Schema(
                                    description = "The caller's roles at the time of the change",
                                    requiredMode = REQUIRED),
                    schema = @Schema(example = "CONTROLLER"))
            List<String> changedByRoles,

            @Schema(
                    description = "AP_CLERK_APPROVAL_LIMIT, AP_AUTO_APPROVAL_LIMIT, AP_ALLOW_CREATOR_APPROVAL,"
                            + " AP_ALLOW_APPROVER_PAYMENT or AP_DEFAULT_TERMS",
                    example = "AP_CLERK_APPROVAL_LIMIT",
                    requiredMode = REQUIRED)
            String setting,

            @Schema(description = "The effective value before", example = "0.00", requiredMode = NOT_REQUIRED) @Nullable
            String oldValue,

            @Schema(description = "The value after", example = "2500.00", requiredMode = REQUIRED)
            String newValue,

            @Schema(
                    description = "Why it changed",
                    example = "Clerks approve routine parts bills up to 2,500",
                    requiredMode = REQUIRED)
            String justification) {

        @Override
        public String toString() {
            // changedByName is a person's name, CONFIDENTIAL (ADR-0072): never printed.
            return "HistoryRow[changedAt=" + changedAt + ", changedBy=" + changedBy + ", changedByRoles="
                    + changedByRoles + ", setting=" + setting + ", oldValue=" + oldValue + ", newValue=" + newValue
                    + ", justification=" + justification + "]";
        }
    }
}
