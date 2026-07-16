package com.positivity.warranty.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Adjudication action (PRD §8 decision endpoint, §5 rules). A decision on a {@code SUBMITTED}
 * claim implicitly begins review ({@code SUBMITTED → IN_REVIEW}) before the decision applies.
 * {@code APPEAL} is only legal on a {@code DENIED} claim and reopens review (PRD §12 —
 * appeal is modeled as {@code DENIED → IN_REVIEW} with a mandatory reason, not a separate
 * entity), which is why it is a request-level action and not a persisted {@code ClaimDecision}.
 */
@Schema(description = "Approve/deny/request-info/appeal a warranty claim")
public record ClaimDecisionRequest(
        @Schema(description = "Decision action") @NotNull Action decision,

        @Schema(
                description = "Reason — required for DENY, APPEAL, and any decision that"
                        + " contradicts the computed suggestion")
        @Size(max = 10_000)
        String reason) {

    /** Request-level action; APPROVE/DENY/REQUEST_INFO persist as {@code ClaimDecision}, APPEAL reopens review. */
    public enum Action {
        APPROVE,
        DENY,
        REQUEST_INFO,
        APPEAL
    }
}
