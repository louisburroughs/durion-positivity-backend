package com.positivity.accounting.internal.bankrec.dto;

import com.positivity.accounting.internal.bankrec.enums.CandidateReason;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Ranked match candidates for one bank transaction (ledger lines) or one ledger line (bank transactions)
 * (SPEC §4.6; story S4, #2303). Deterministic: score descending, then date distance, then id.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Ranked, explained match candidates")
public class ReconciliationCandidatesResponse {

    @Schema(description = "The bank transaction the candidates are for, when asked by bankTransactionId")
    private UUID bankTransactionId;

    @Schema(description = "The ledger line the candidates are for, when asked by glLineId")
    private UUID glLineId;

    @Schema(description = "The date window applied, in days", example = "7")
    private int windowDays;

    @Schema(description = "Candidates, best first")
    private List<Candidate> candidates;

    /** One candidate: a ledger line (for a bank transaction) or a bank transaction (for a ledger line). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "One match candidate with its score and reason codes")
    public static class Candidate {

        @Schema(description = "The candidate ledger line (asked by bankTransactionId)")
        private UUID glLineId;

        @Schema(description = "The candidate's journal entry")
        private UUID journalEntryId;

        @Schema(description = "The candidate's posted entry number", example = "JE-202609-0042")
        private String entryNumber;

        @Schema(description = "The candidate bank transaction (asked by glLineId)")
        private UUID bankTransactionId;

        @Schema(description = "Candidate date", example = "2026-09-12")
        private LocalDate date;

        @Schema(description = "Candidate signed amount", example = "250.0000")
        private BigDecimal signedAmount;

        @Schema(description = "Candidate description")
        private String description;

        @Schema(description = "Score 0–110", example = "80")
        private int score;

        @Schema(description = "Reason codes of the score")
        private List<CandidateReason> reasons;

        @Schema(description = "Days between the candidate and the subject", example = "1")
        private long dateDistance;
    }
}
