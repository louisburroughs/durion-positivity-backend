package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationGlMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.enums.InvalidationReason;
import com.positivity.accounting.internal.bankrec.enums.MatchState;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.event.LedgerPostingApplied;
import com.positivity.accounting.internal.event.LedgerReversalApplied;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ledger-change hook of the bank reconciliation (SPEC-manual-bank-reconciliation §5.5, §3.6, §4.9 path 2,
 * I3, I4, D11; story S5, #2304). It hears the in-process events {@code JournalEntryServiceImpl} publishes after
 * a posting and after a reversal, and runs synchronously in that transaction; it never refuses the ledger
 * change and never reads bank data (D10).
 *
 * <ul>
 *   <li><b>Posting</b>: a {@code FINALIZED} reconciliation of an account the entry touches whose window contains
 *       the entry's date becomes {@code INVALIDATED} ({@code LEDGER_LINE_POSTED}) — whether the period was open,
 *       reopened or overridden, and for a {@code TRANSFER}'s counter line on the counter account too.
 *   <li><b>Reversal</b>: every active match holding a line of the original becomes {@code BROKEN} ({@code
 *       brokenByJournalEntryId} = the reversal), its members inactive and its bank rows {@code UNMATCHED} —
 *       a residual settlement's replacement match included; every {@code OPEN} outstanding item on a line of the
 *       original becomes {@code VOIDED} with {@code closedOn} = the reversal's date; the {@code FINALIZED}
 *       owners of the broken matches, and a {@code FINALIZED} window containing the reversal's date, become
 *       {@code INVALIDATED} ({@code LEDGER_LINE_REVERSED}).
 * </ul>
 *
 * <p>The {@code SUBMITTED} and {@code FINALIZED} reconciliations concerned are row-locked first, so a posting
 * and an approval of the same window serialize: whichever commits second sees the other (AC 9).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankReconciliationLedgerChangeService {

    private static final List<ReconciliationStatus> LOCKED =
            List.of(ReconciliationStatus.SUBMITTED, ReconciliationStatus.FINALIZED);

    private final BankReconciliationRepository reconciliations;
    private final BankReconciliationMatchRepository matches;
    private final BankReconciliationGlMatchRepository glMatches;
    private final BankReconciliationOutstandingItemRepository items;
    private final MatchWriter writer;
    private final ReconciliationLifecycle lifecycle;

    /** A posting: invalidate the approved windows it lands in. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPosted(@NonNull LedgerPostingApplied posted) {
        if (posted.glAccountIds().isEmpty()) {
            return;
        }
        for (BankReconciliation recon :
                reconciliations.lockCovering(posted.glAccountIds(), LOCKED, posted.transactionDate())) {
            lifecycle.invalidate(recon, InvalidationReason.LEDGER_LINE_POSTED, posted.journalEntryId(), posted.actor());
        }
    }

    /** A reversal: break the matches, void the items, invalidate the approvals resting on the original. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onReversed(@NonNull LedgerReversalApplied reversed) {
        List<UUID> lines = reversed.originalLineIds();
        if (lines.isEmpty()) {
            return;
        }
        UUID reversalId = reversed.reversalJournalEntryId();
        String actor = reversed.actor();

        // The active matches on the original's lines, and the reconciliations to lock before changing them.
        Set<UUID> matchIds = new LinkedHashSet<>();
        for (BankReconciliationGlMatch member : glMatches.findByGlLineIdInAndActiveTrue(lines)) {
            matchIds.add(member.getMatchId());
        }
        List<BankReconciliationMatch> broken = matchIds.isEmpty() ? List.of() : matches.findAllById(matchIds);
        Map<UUID, BankReconciliation> concerned = new LinkedHashMap<>();
        Set<UUID> owners = new LinkedHashSet<>();
        broken.forEach(m -> owners.add(m.getReconciliationId()));
        if (!owners.isEmpty()) {
            reconciliations.lockByIds(owners).forEach(r -> concerned.put(r.getReconciliationId(), r));
        }
        List<BankReconciliation> covering = reversed.glAccountIds().isEmpty()
                ? List.of()
                : reconciliations.lockCovering(reversed.glAccountIds(), LOCKED, reversed.reversalDate());

        for (BankReconciliationMatch match : broken) {
            if (match.getState() != MatchState.ACCEPTED && match.getState() != MatchState.PROPOSED) {
                continue;
            }
            match.setBrokenByJournalEntryId(reversalId);
            writer.end(match, MatchState.BROKEN, null, actor);
        }

        List<BankReconciliationOutstandingItem> voided =
                items.findByGlLineIdInAndStatus(lines, OutstandingItemStatus.OPEN);
        for (BankReconciliationOutstandingItem item : voided) {
            item.setStatus(OutstandingItemStatus.VOIDED);
            item.setVoidedByJournalEntryId(reversalId);
            item.setClosedOn(reversed.reversalDate());
        }
        items.saveAll(voided);

        List<BankReconciliation> toInvalidate = new ArrayList<>(concerned.values());
        covering.stream()
                .filter(r -> !concerned.containsKey(r.getReconciliationId()))
                .forEach(toInvalidate::add);
        int invalidated = 0;
        for (BankReconciliation recon : toInvalidate) {
            if (lifecycle.invalidate(recon, InvalidationReason.LEDGER_LINE_REVERSED, reversalId, actor)) {
                invalidated++;
            }
        }
        if (!broken.isEmpty() || !voided.isEmpty() || invalidated > 0) {
            log.info(
                    "Reversal {} of journal entry {}: {} match(es) broken, {} item(s) voided, {} reconciliation(s)"
                            + " invalidated",
                    reversalId,
                    reversed.originalJournalEntryId(),
                    broken.size(),
                    voided.size(),
                    invalidated);
        }
    }
}
