package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Change the AP approval policy (CAP:550 S13, #2510; SPEC-accounting-workspace §4.3, §5.5; AW4-AW6, AW33). Every
 * setting is optional: a missing one is unchanged. The actor is the caller; a body field naming one ({@code
 * approvedBy}, {@code operatorId}) is ignored.
 */
@Schema(
        name = "ApApprovalPolicyRequest",
        description = "The AP approval policy settings to change, with the justification and the request id")
public record ApApprovalPolicyRequest(
        @Schema(
                description = "How much a clerk (accounting:ap:approve) may approve, the bill's absolute total"
                        + " including tax; at least 0, in the functional currency. 0 means every bill needs an"
                        + " over-limit approver. Missing means unchanged",
                example = "2500.00",
                requiredMode = NOT_REQUIRED)
        @Nullable
        BigDecimal clerkApprovalLimit,

        @Schema(
                description = "Up to how much the system approves a strong (HIGH) goods-receipt match; at least 0 and"
                        + " at most the clerk limit. 0 turns automatic approval off. Missing means unchanged",
                example = "500.00",
                requiredMode = NOT_REQUIRED)
        @Nullable
        BigDecimal autoApprovalLimit,

        @Schema(
                description = "ISO 4217 code of the limits; required when a limit is given, and it must be the"
                        + " functional currency (422 CURRENCY_NOT_SUPPORTED otherwise)",
                example = "USD",
                minLength = 3,
                maxLength = 3,
                requiredMode = NOT_REQUIRED)
        @Nullable
        String currencyCode,

        @Schema(
                description = "Whether a bill's creator may approve it, with a justification (separation-of-duties"
                        + " exception, default false). Missing means unchanged",
                example = "false",
                requiredMode = NOT_REQUIRED)
        @Nullable
        Boolean allowCreatorApproval,

        @Schema(
                description = "Whether a bill's approver may pay it (separation-of-duties exception, default false)."
                        + " Missing means unchanged",
                example = "false",
                requiredMode = NOT_REQUIRED)
        @Nullable
        Boolean allowApproverPayment,

        @Schema(
                description = "The default AP terms: DUE_ON_RECEIPT or NET<n>, n an integer from 1 to 120, upper"
                        + " case, no spaces (default NET30). Missing means unchanged",
                example = "NET30",
                requiredMode = NOT_REQUIRED)
        @Size(max = 20)
        @Nullable
        String defaultTerms,

        @Schema(
                description = "Why the policy changes; at least 10 characters (400 JUSTIFICATION_REQUIRED otherwise),"
                        + " recorded on every AP_APPROVAL_POLICY_SET audit row",
                example = "Clerks approve routine parts bills up to 2,500",
                requiredMode = REQUIRED)
        @Size(max = 1000)
        @Nullable
        String justification,

        @Schema(
                description = "Generated once per user intent by the client (§8.2): a replay with a request id"
                        + " already recorded writes nothing and returns the current policy",
                example = "0199c0de-7a1b-7c2d-8e3f-4a5b6c7d8e9f",
                requiredMode = REQUIRED)
        @Nullable
        UUID requestId) {}
