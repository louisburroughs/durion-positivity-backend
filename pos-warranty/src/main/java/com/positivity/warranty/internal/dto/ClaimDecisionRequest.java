package com.positivity.warranty.internal.dto;

import com.positivity.warranty.internal.enums.LineDisposition;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Adjudication action (PRD §8 decision endpoint, §5 rules). A decision on a {@code SUBMITTED}
 * claim implicitly begins review ({@code SUBMITTED → IN_REVIEW}) before the decision applies.
 * {@code APPEAL} is only legal on a {@code DENIED} claim and reopens review (PRD §12 —
 * appeal is modeled as {@code DENIED → IN_REVIEW} with a mandatory reason, not a separate
 * entity), which is why it is a request-level action and not a persisted {@code ClaimDecision}.
 *
 * <p>{@code lineDecisions} (PRD §3.5, §6) optionally record per-line approved amounts and
 * dispositions on APPROVE/DENY; lines without an entry default to the computed
 * {@code amountRequested} (APPROVED) or to DENIED. An {@code amountApproved} that differs from
 * the computed amount requires an {@code overrideReason}, which is audited as a claim note.
 */
@Schema(description = "Approve/deny/request-info/appeal a warranty claim")
public record ClaimDecisionRequest(
        @Schema(description = "Decision action") @NotNull Action decision,

        @Schema(
                description = "Reason — required for DENY, APPEAL, and any decision that"
                        + " contradicts the computed suggestion")
        @Size(max = 10_000)
        String reason,

        @Schema(description = "Per-line adjudication (APPROVE/DENY only); omitted lines get defaults") @Valid
        List<LineDecision> lineDecisions) {

    /** Convenience for claim-level decisions without per-line adjudication. */
    public ClaimDecisionRequest(Action decision, String reason) {
        this(decision, reason, null);
    }

    /** Request-level action; APPROVE/DENY/REQUEST_INFO persist as {@code ClaimDecision}, APPEAL reopens review. */
    public enum Action {
        APPROVE,
        DENY,
        REQUEST_INFO,
        APPEAL
    }

    /** Per-line adjudication entry (PRD §3.5 {@code amountApproved}/{@code lineDisposition}). */
    @Schema(description = "Per-line approved amount and disposition")
    public record LineDecision(
            @Schema(description = "Claim line UUID") @NotNull
            UUID lineId,

            @Schema(description = "Approved amount; defaults to the computed amountRequested on APPROVE")
            BigDecimal amountApproved,

            @Schema(description = "Line disposition; defaults to APPROVED on APPROVE, DENIED on DENY")
            LineDisposition lineDisposition,

            @Schema(
                    description = "Required when amountApproved differs from the computed"
                            + " amountRequested; audited as a claim note")
            @Size(max = 10_000)
            String overrideReason) {}
}
