package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Whether a marketing message may be sent to a party on a channel, and why (Story #1138).
 *
 * <p>The {@code reason} is returned on allow as well as deny so audience previews can
 * explain a shrinking recipient count without a second call.
 */
@Schema(description = "Marketing send eligibility decision for a party and channel")
public record MarketingConsentDecision(
        @Schema(description = "Party the decision is about", requiredMode = REQUIRED)
        UUID partyId,

        @Schema(
                description = "Channel evaluated",
                example = "EMAIL",
                allowableValues = {"EMAIL", "SMS"},
                requiredMode = REQUIRED)
        String channel,

        @Schema(description = "Whether a marketing send is permitted", example = "false", requiredMode = REQUIRED)
        boolean allowed,

        @Schema(
                description = "Machine-readable decision reason",
                example = "ACCOUNT_OPT_OUT",
                allowableValues = {
                    "OPT_IN",
                    "ACCOUNT_OPT_OUT",
                    "CONSENT_OPT_OUT",
                    "CONSENT_UNSET",
                    "NO_PRIMARY_CONTACT",
                    "SUPPRESSED",
                    "PARTY_NOT_FOUND"
                },
                requiredMode = REQUIRED)
        String reason,

        @Schema(
                description =
                        "Party whose personal consent governed the decision — the primary business contact for a commercial account, the party itself for an individual",
                requiredMode = NOT_REQUIRED)
        UUID governingPartyId) {

    public static final String REASON_OPT_IN = "OPT_IN";
    public static final String REASON_ACCOUNT_OPT_OUT = "ACCOUNT_OPT_OUT";
    public static final String REASON_CONSENT_OPT_OUT = "CONSENT_OPT_OUT";
    public static final String REASON_CONSENT_UNSET = "CONSENT_UNSET";
    public static final String REASON_NO_PRIMARY_CONTACT = "NO_PRIMARY_CONTACT";
    public static final String REASON_SUPPRESSED = "SUPPRESSED";
    public static final String REASON_PARTY_NOT_FOUND = "PARTY_NOT_FOUND";
}
