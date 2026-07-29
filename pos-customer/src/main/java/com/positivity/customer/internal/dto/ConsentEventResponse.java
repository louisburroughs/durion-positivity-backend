package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "One immutable entry in a party's marketing-consent audit trail")
public record ConsentEventResponse(
        @Schema(description = "Consent event identifier", requiredMode = REQUIRED)
        UUID consentEventId,

        @Schema(description = "Party the change applies to", requiredMode = REQUIRED)
        UUID partyId,

        @Schema(
                description = "Channel changed",
                example = "EMAIL",
                allowableValues = {"EMAIL", "SMS"},
                requiredMode = REQUIRED)
        String channel,

        @Schema(description = "Consent value before the change", example = "UNSET", requiredMode = REQUIRED)
        String oldValue,

        @Schema(description = "Consent value after the change", example = "OPT_IN", requiredMode = REQUIRED)
        String newValue,

        @Schema(description = "Opt-out reason, when the change was a withdrawal", requiredMode = NOT_REQUIRED)
        String reason,

        @Schema(description = "Where the change originated", example = "CSR", requiredMode = REQUIRED)
        String source,

        @Schema(description = "Actor that made the change", requiredMode = NOT_REQUIRED)
        String actor,

        @Schema(description = "When the change occurred", requiredMode = REQUIRED)
        Instant occurredAt) {}
