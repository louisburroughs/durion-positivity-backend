package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationBankMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationGlMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationMatch;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.InvalidationReason;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationBankMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.MatchWriter.LedgerMember;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The reconciliation transitions the system makes, shared by the approval workflow, the ledger-change hook
 * and statement supersession (SPEC-manual-bank-reconciliation §3.8, §4.9, §5.5; story S5, #2304): invalidate
 * an approved reconciliation, release a sealed reconciliation's match members, and mark a predecessor
 * superseded when its successor is approved. Each writes its audit row and its outbox fact in the caller's
 * transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationLifecycle {

    private final Clock clock;
    private final BankReconciliationRepository reconciliations;
    private final BankReconciliationMatchRepository matches;
    private final BankReconciliationGlMatchRepository glMatches;
    private final BankReconciliationBankMatchRepository bankMatches;
    private final BankTransactionRepository transactions;
    private final BankRecAuditRecorder audit;
    private final BankReconciliationFacts facts;

    /** A match whose members were released: its header and the members a successor re-proposes. */
    public record ReleasedMatch(
            @NonNull BankReconciliationMatch match,
            @NonNull List<UUID> bankTransactionIds,
            @NonNull List<LedgerMember> ledgerMembers) {}

    /**
     * {@code FINALIZED → INVALIDATED} with the reason and the triggering journal entry, one {@code
     * RECONCILIATION_INVALIDATE} audit row naming {@code actor} and the {@code .invalidated} fact (§5.5). A
     * reconciliation in any other status is left alone.
     *
     * @return whether the reconciliation was invalidated
     */
    public boolean invalidate(
            @NonNull BankReconciliation recon,
            @NonNull InvalidationReason reason,
            @Nullable UUID journalEntryId,
            @NonNull String actor) {
        if (recon.getStatus() != ReconciliationStatus.FINALIZED) {
            return false;
        }
        recon.setStatus(ReconciliationStatus.INVALIDATED);
        recon.setInvalidatedAt(Instant.now(clock));
        recon.setInvalidationReason(reason.name());
        recon.setInvalidatedByJournalEntryId(journalEntryId);
        reconciliations.save(recon);
        audit.record(
                BankRecAuditRecorder.BANK_RECONCILIATION,
                recon.getReconciliationId(),
                BankRecAuditRecorder.RECONCILIATION_INVALIDATE,
                actor,
                null,
                "status=" + ReconciliationStatus.FINALIZED,
                "status=" + ReconciliationStatus.INVALIDATED + ";reason=" + reason
                        + (journalEntryId != null ? ";journalEntryId=" + journalEntryId : ""));
        facts.invalidated(recon, reason, journalEntryId, actor);
        log.info(
                "Invalidated reconciliation {} ({}, journal entry {})",
                recon.getReconciliationId(),
                reason,
                journalEntryId);
        return true;
    }

    /**
     * Releases the active members of every match of {@code recon}: members {@code active = false}, their bank
     * rows back to {@code UNMATCHED}, the headers keeping their state as sealed history (§4.9 paths 1 and 3).
     * Members are flushed inactive before returning, so a successor can propose them at once.
     *
     * @return the matches released, oldest first, with their members
     */
    public @NonNull List<ReleasedMatch> releaseMembers(@NonNull BankReconciliation recon) {
        List<ReleasedMatch> released = new ArrayList<>();
        for (BankReconciliationMatch match :
                matches.findByReconciliationIdOrderByCreatedAtAsc(recon.getReconciliationId())) {
            List<BankReconciliationBankMatch> bankMembers = bankMatches.findByMatchIdAndActiveTrue(match.getMatchId());
            List<BankReconciliationGlMatch> glMembers = glMatches.findByMatchIdAndActiveTrue(match.getMatchId());
            if (bankMembers.isEmpty() && glMembers.isEmpty()) {
                continue;
            }
            List<UUID> bankIds = bankMembers.stream()
                    .map(BankReconciliationBankMatch::getBankTransactionId)
                    .toList();
            List<BankTransaction> rows = transactions.findAllById(bankIds);
            rows.stream()
                    .filter(t -> t.getStatus() == BankTransactionStatus.MATCHED)
                    .forEach(t -> t.setStatus(BankTransactionStatus.UNMATCHED));
            transactions.saveAll(rows);
            bankMembers.forEach(m -> m.setActive(false));
            bankMatches.saveAll(bankMembers);
            glMembers.forEach(m -> m.setActive(false));
            glMatches.saveAll(glMembers);
            released.add(new ReleasedMatch(
                    match,
                    bankIds,
                    glMembers.stream()
                            .map(m -> new LedgerMember(m.getGlLineId(), m.getSignedAmount()))
                            .toList()));
        }
        bankMatches.flush();
        glMatches.flush();
        return released;
    }

    /**
     * {@code FINALIZED | INVALIDATED → SUPERSEDED} once {@code successor} is approved: {@code
     * supersededByReconciliationId}, one {@code RECONCILIATION_SUPERSEDE} audit row on the predecessor and the
     * {@code .superseded} fact (§4.9).
     */
    public void markSuperseded(
            @NonNull BankReconciliation predecessor, @NonNull BankReconciliation successor, @NonNull String actor) {
        ReconciliationStatus previous = predecessor.getStatus();
        predecessor.setStatus(ReconciliationStatus.SUPERSEDED);
        predecessor.setSupersededByReconciliationId(successor.getReconciliationId());
        reconciliations.save(predecessor);
        audit.record(
                BankRecAuditRecorder.BANK_RECONCILIATION,
                predecessor.getReconciliationId(),
                BankRecAuditRecorder.RECONCILIATION_SUPERSEDE,
                actor,
                null,
                "status=" + previous,
                "status=" + ReconciliationStatus.SUPERSEDED + ";supersededByReconciliationId="
                        + successor.getReconciliationId());
        facts.superseded(predecessor, successor.getReconciliationId(), actor);
        log.info(
                "Reconciliation {} superseded by approved reconciliation {}",
                predecessor.getReconciliationId(),
                successor.getReconciliationId());
    }
}
