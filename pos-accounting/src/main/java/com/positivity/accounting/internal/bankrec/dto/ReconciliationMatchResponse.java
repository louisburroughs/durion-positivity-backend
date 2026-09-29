package com.positivity.accounting.internal.bankrec.dto;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliationMatch;
import com.positivity.accounting.internal.bankrec.enums.MatchKind;
import com.positivity.accounting.internal.bankrec.enums.MatchOrigin;
import com.positivity.accounting.internal.bankrec.enums.MatchState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A reconciliation match (SPEC §3.4; story S4, #2303). {@code residual = bankTotal − ledgerTotal} is served,
 * never stored, so a client never computes it; a residual settlement posts exactly this value.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Reconciliation match with its members, totals, tolerance and signed residual")
public class ReconciliationMatchResponse {

    @Schema(description = "Match id")
    private UUID matchId;

    @Schema(description = "Reconciliation the match belongs to")
    private UUID reconciliationId;

    @Schema(description = "Cardinality", example = "ONE_TO_ONE")
    private MatchKind matchKind;

    @Schema(description = "State", example = "ACCEPTED")
    private MatchState state;

    @Schema(description = "Who proposed it: USER or RULE (a RULE match is never accepted by the system)")
    private MatchOrigin origin;

    @Schema(description = "0–100 for a rule proposal; null for a user match", example = "95")
    private Integer confidenceScore;

    @Schema(description = "Candidate reason codes of a rule proposal")
    private List<String> reasons;

    @Schema(description = "Σ bank members", example = "99.9900")
    private BigDecimal bankTotal;

    @Schema(description = "Σ ledger members (debit − credit)", example = "100.0000")
    private BigDecimal ledgerTotal;

    @Schema(description = "abs(bankTotal − ledgerTotal), at most 0.01", example = "0.0100")
    private BigDecimal toleranceUsed;

    @Schema(description = "bankTotal − ledgerTotal: what a residual settlement posts", example = "-0.0100")
    private BigDecimal residual;

    @Schema(description = "Justification recorded for the match")
    private String justification;

    @Schema(description = "Bank members (current members; history once unmatched)")
    private List<UUID> bankTransactionIds;

    @Schema(description = "Ledger members (current members; history once unmatched)")
    private List<UUID> glLineIds;

    private String proposedBy;
    private Instant proposedAt;
    private String acceptedBy;
    private Instant acceptedAt;
    private String rejectedBy;
    private Instant rejectedAt;
    private String unmatchedBy;
    private Instant unmatchedAt;

    @Schema(description = "Free text, or RESIDUAL_SETTLED when a residual settlement replaced the match")
    private String unmatchReason;

    @Schema(description = "The match a residual settlement replaced")
    private UUID replacesMatchId;

    @Schema(description = "True when this answers a replayed match command")
    private boolean replayed;

    /** The match with its members. */
    public static ReconciliationMatchResponse from(
            BankReconciliationMatch m, List<UUID> bankTransactionIds, List<UUID> glLineIds) {
        return ReconciliationMatchResponse.builder()
                .matchId(m.getMatchId())
                .reconciliationId(m.getReconciliationId())
                .matchKind(m.getMatchKind())
                .state(m.getState())
                .origin(m.getOrigin())
                .confidenceScore(m.getConfidenceScore())
                .reasons(m.getReasons())
                .bankTotal(m.getBankTotal())
                .ledgerTotal(m.getLedgerTotal())
                .toleranceUsed(m.getToleranceUsed())
                .residual(
                        m.getBankTotal() != null && m.getLedgerTotal() != null
                                ? m.getBankTotal().subtract(m.getLedgerTotal())
                                : null)
                .justification(m.getJustification())
                .bankTransactionIds(bankTransactionIds)
                .glLineIds(glLineIds)
                .proposedBy(m.getProposedBy())
                .proposedAt(m.getProposedAt())
                .acceptedBy(m.getAcceptedBy())
                .acceptedAt(m.getAcceptedAt())
                .rejectedBy(m.getRejectedBy())
                .rejectedAt(m.getRejectedAt())
                .unmatchedBy(m.getUnmatchedBy())
                .unmatchedAt(m.getUnmatchedAt())
                .unmatchReason(m.getUnmatchReason())
                .replacesMatchId(m.getReplacesMatchId())
                .build();
    }
}
