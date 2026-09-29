package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationBankMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationGlMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.MatchKind;
import com.positivity.accounting.internal.bankrec.enums.MatchOrigin;
import com.positivity.accounting.internal.bankrec.enums.MatchState;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemSide;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationBankMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.exception.ReconciliationLineIneligibleException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Writes match headers and members (SPEC §3.4, §5.4; story S4, #2303) for every path that creates or ends a
 * match: a human match, an accepted or rejected proposal, an unmatch, an adjustment's {@code ADJUSTMENT}
 * match and a residual settlement's replacement. It keeps the denormalized {@code active} flag, the bank rows'
 * {@code MATCHED} / {@code UNMATCHED} status and the clearing of ledger-side outstanding items in step with the
 * header, and maps a lost race on the active partial uniques (U4) to 409 {@code RECONCILIATION_LINE_INELIGIBLE}.
 */
@Component
@RequiredArgsConstructor
public class MatchWriter {

    private final Clock clock;
    private final BankReconciliationMatchRepository matches;
    private final BankReconciliationGlMatchRepository glMatches;
    private final BankReconciliationBankMatchRepository bankMatches;
    private final BankTransactionRepository transactions;
    private final BankReconciliationOutstandingItemRepository items;

    /** A ledger member: a line and its signed amount on the reconciled account. */
    public record LedgerMember(
            @NonNull UUID glLineId, @NonNull BigDecimal signedAmount) {

        public static @NonNull LedgerMember of(@NonNull LedgerLine line) {
            return new LedgerMember(line.lineId(), line.signedAmount());
        }
    }

    /** What a new match header carries beyond its members. */
    public record Header(
            @NonNull MatchKind kind,
            @NonNull MatchState state,
            @NonNull MatchOrigin origin,
            @Nullable Integer confidenceScore,
            @Nullable List<String> reasons,
            @Nullable String justification,
            @Nullable UUID requestId,
            @Nullable UUID replacesMatchId) {}

    /**
     * Creates a match with active members. An {@code ACCEPTED} match marks its bank rows {@code MATCHED} and
     * clears the {@code OPEN} ledger-side items on its lines in the same transaction (§5.4); a {@code PROPOSED}
     * one only reserves its members.
     */
    public @NonNull BankReconciliationMatch create(
            @NonNull BankReconciliation recon,
            @NonNull Header header,
            @NonNull List<BankTransaction> bankRows,
            @NonNull List<LedgerMember> ledgerMembers,
            @NonNull String actor) {
        Instant now = Instant.now(clock);
        BigDecimal bankTotal =
                bankRows.stream().map(BankTransaction::getSignedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ledgerTotal =
                ledgerMembers.stream().map(LedgerMember::signedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        BankReconciliationMatch match = new BankReconciliationMatch();
        match.setReconciliationId(recon.getReconciliationId());
        match.setMatchKind(header.kind());
        match.setState(header.state());
        match.setOrigin(header.origin());
        match.setConfidenceScore(header.confidenceScore());
        match.setReasons(header.reasons());
        match.setBankTotal(bankTotal);
        match.setLedgerTotal(ledgerTotal);
        match.setToleranceUsed(bankTotal.subtract(ledgerTotal).abs());
        match.setJustification(header.justification());
        match.setRequestId(header.requestId());
        match.setReplacesMatchId(header.replacesMatchId());
        match.setProposedBy(actor);
        match.setProposedAt(now);
        if (header.state() == MatchState.ACCEPTED) {
            match.setAcceptedBy(actor);
            match.setAcceptedAt(now);
        }
        BankReconciliationMatch saved = matches.save(match);
        UUID matchId = saved.getMatchId();

        List<BankReconciliationGlMatch> glMembers = ledgerMembers.stream()
                .map(member -> {
                    BankReconciliationGlMatch m = new BankReconciliationGlMatch();
                    m.setReconciliationId(recon.getReconciliationId());
                    m.setMatchId(matchId);
                    m.setGlLineId(member.glLineId());
                    m.setSignedAmount(member.signedAmount());
                    m.setActive(true);
                    return m;
                })
                .toList();
        List<BankReconciliationBankMatch> bankMembers = bankRows.stream()
                .map(t -> new BankReconciliationBankMatch(matchId, t.getBankTransactionId()))
                .toList();
        try {
            // Flush inside the guard: a concurrent match that raced past the eligibility checks surfaces the
            // partial unique (gl_line_id / bank_transaction_id) WHERE active as a 409 here, not a 500 at commit.
            bankMatches.saveAll(bankMembers);
            glMatches.saveAllAndFlush(glMembers);
        } catch (DataIntegrityViolationException e) {
            throw new ReconciliationLineIneligibleException(
                    "A bank transaction or GL line of this match was concurrently matched");
        }
        if (header.state() == MatchState.ACCEPTED) {
            accept(
                    recon,
                    saved,
                    bankRows,
                    ledgerMembers.stream().map(LedgerMember::glLineId).toList(),
                    actor);
        }
        return saved;
    }

    /**
     * Marks the bank rows {@code MATCHED} and clears the {@code OPEN} ledger-side items on the lines: {@code
     * CLEARED} in this reconciliation by this match, {@code closedOn} the latest bank-member date (§3.6, §5.4).
     */
    public void accept(
            @NonNull BankReconciliation recon,
            @NonNull BankReconciliationMatch match,
            @NonNull List<BankTransaction> bankRows,
            @NonNull Collection<UUID> glLineIds,
            @NonNull String actor) {
        bankRows.forEach(t -> t.setStatus(BankTransactionStatus.MATCHED));
        transactions.saveAll(bankRows);
        LocalDate closedOn = bankRows.stream()
                .map(BankTransaction::getTransactionDate)
                .max(Comparator.naturalOrder())
                .orElse(recon.getStatementEndDate());
        Instant now = Instant.now(clock);
        List<BankReconciliationOutstandingItem> cleared =
                items.findByGlLineIdInAndStatus(glLineIds, OutstandingItemStatus.OPEN).stream()
                        .filter(i -> i.getSide() == OutstandingItemSide.LEDGER)
                        .toList();
        for (BankReconciliationOutstandingItem item : cleared) {
            item.setStatus(OutstandingItemStatus.CLEARED);
            item.setClearedInReconciliationId(recon.getReconciliationId());
            item.setClearedByMatchId(match.getMatchId());
            item.setClearedAt(now);
            item.setClearedBy(actor);
            item.setClosedOn(closedOn);
        }
        items.saveAll(cleared);
    }

    /**
     * Re-opens the ledger-side items {@code matchId} cleared: {@code OPEN} again with the closure fields cleared,
     * so a later match (or the successor's re-accepted one) clears them anew. Shared by unmatch and by the
     * member release of supersede (§4.9 paths 1 and 3).
     */
    static void reopenCleared(@NonNull BankReconciliationOutstandingItemRepository items, @NonNull UUID matchId) {
        List<BankReconciliationOutstandingItem> reopened =
                items.findByClearedByMatchIdAndStatus(matchId, OutstandingItemStatus.CLEARED);
        for (BankReconciliationOutstandingItem item : reopened) {
            item.setStatus(OutstandingItemStatus.OPEN);
            item.setClearedInReconciliationId(null);
            item.setClearedByMatchId(null);
            item.setClearedAt(null);
            item.setClearedBy(null);
            item.setClosedOn(null);
        }
        items.saveAll(reopened);
    }

    /**
     * Ends a live match: the header moves to {@code state} (UNMATCHED, REJECTED) with the actor and reason, its
     * members go inactive and its bank rows return to {@code UNMATCHED}. An unmatch re-opens the items this match
     * cleared. Members are flushed inactive before returning, so a replacement can re-use them at once.
     */
    public void end(
            @NonNull BankReconciliationMatch match,
            @NonNull MatchState state,
            @Nullable String reason,
            @NonNull String actor) {
        Instant now = Instant.now(clock);
        List<BankReconciliationBankMatch> bankMembers = bankMatches.findByMatchIdAndActiveTrue(match.getMatchId());
        List<BankTransaction> rows = transactions.findAllById(bankMembers.stream()
                .map(BankReconciliationBankMatch::getBankTransactionId)
                .toList());
        rows.forEach(t -> t.setStatus(BankTransactionStatus.UNMATCHED));
        transactions.saveAll(rows);
        bankMembers.forEach(m -> m.setActive(false));
        bankMatches.saveAll(bankMembers);
        List<BankReconciliationGlMatch> glMembers = glMatches.findByMatchIdAndActiveTrue(match.getMatchId());
        glMembers.forEach(m -> m.setActive(false));
        glMatches.saveAllAndFlush(glMembers);

        if (state == MatchState.UNMATCHED) {
            reopenCleared(items, match.getMatchId());
            match.setUnmatchedBy(actor);
            match.setUnmatchedAt(now);
            match.setUnmatchReason(reason);
        } else if (state == MatchState.REJECTED) {
            match.setRejectedBy(actor);
            match.setRejectedAt(now);
        }
        match.setState(state);
        matches.save(match);
    }
}
