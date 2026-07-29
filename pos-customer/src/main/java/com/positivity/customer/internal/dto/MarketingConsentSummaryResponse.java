package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

@Schema(description = "Per-channel marketing consent and suppression state for a party")
public record MarketingConsentSummaryResponse(
        @Schema(description = "Party identifier", requiredMode = REQUIRED)
        UUID partyId,

        @Schema(
                description = "Marketing consent for email",
                example = "OPT_IN",
                allowableValues = {"OPT_IN", "OPT_OUT", "UNSET"},
                requiredMode = REQUIRED)
        String marketingEmailConsent,

        @Schema(
                description = "Marketing consent for SMS",
                example = "UNSET",
                allowableValues = {"OPT_IN", "OPT_OUT", "UNSET"},
                requiredMode = REQUIRED)
        String marketingSmsConsent,

        @Schema(description = "Reason marketing consent was last withdrawn", requiredMode = NOT_REQUIRED)
        String optOutReason,

        @Schema(description = "When marketing consent was last withdrawn", requiredMode = NOT_REQUIRED)
        Instant optOutAt,

        @Schema(
                description = "Account-level hard gate; only meaningful for commercial parties, null for individuals",
                requiredMode = NOT_REQUIRED)
        Boolean accountMarketingOptOut,

        @Schema(description = "Start of the quiet-hours window", requiredMode = NOT_REQUIRED)
        LocalTime quietHoursStart,

        @Schema(description = "End of the quiet-hours window", requiredMode = NOT_REQUIRED)
        LocalTime quietHoursEnd,

        @Schema(description = "Cadence cap on marketing sends per month", requiredMode = NOT_REQUIRED)
        Integer maxMarketingSendsPerMonth,

        @Schema(
                description = "Effective email eligibility after consent, account gate, and suppression",
                requiredMode = REQUIRED)
        MarketingConsentDecision emailEligibility,

        @Schema(
                description = "Effective SMS eligibility after consent, account gate, and suppression",
                requiredMode = REQUIRED)
        MarketingConsentDecision smsEligibility) {}
