package com.positivity.shopmanager.internal.service.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/** The override rows written for one request, with the rule each one accepted. */
@Value
@Builder
@Schema(description = "Result of a manager override of SOFT scheduling conflicts")
public class ConflictOverrideResponse {

    @Schema(description = "Appointment the conflicts belong to", requiredMode = REQUIRED)
    UUID appointmentId;

    @Schema(
            description = "Manager who recorded the override; also the approver (single-actor approval)",
            example = "jane.manager",
            requiredMode = REQUIRED)
    String overriddenBy;

    @Schema(description = "When the override was recorded and approved, UTC", requiredMode = REQUIRED)
    Instant approvedAt;

    @Schema(description = "Justification as recorded", requiredMode = REQUIRED)
    String overrideReason;

    @Schema(description = "One entry per conflict accepted", requiredMode = REQUIRED)
    List<OverrideEntry> overrides;

    @Value
    @Builder
    @Schema(description = "One accepted conflict")
    public static class OverrideEntry {
        @Schema(description = "The immutable override row", requiredMode = REQUIRED)
        UUID overrideId;

        @Schema(description = "The conflict accepted", requiredMode = REQUIRED)
        UUID conflictId;

        @Schema(
                description = "The rule that fired — the API reason code verbatim",
                example = "MECHANIC_OVERTIME",
                requiredMode = REQUIRED)
        String ruleCode;

        @Schema(description = "Always SOFT: a HARD conflict is never overridden", example = "SOFT", requiredMode = REQUIRED)
        String severity;
    }
}
