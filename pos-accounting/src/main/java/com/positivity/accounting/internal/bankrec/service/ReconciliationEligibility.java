package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationBankMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationGlMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.enums.SettlementState;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationBankMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.exception.ReconciliationLineIneligibleException;
import com.positivity.accounting.internal.exception.ReconciliationNotFoundException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Which bank transactions and ledger lines a reconciliation may put in a match or an outstanding item (SPEC
 * §3.4 M3/M4, §3.6 O1, D19; story S4, #2303). Every check reads the rows under a row lock, so a match and a
 * registration naming the same row serialize and a row never ends in both an active match and an OPEN item.
 */
@Component
@RequiredArgsConstructor
public class ReconciliationEligibility {

    /** The audit operation of a duplicate review (S2); a row reviewed DISTINCT is a former possible duplicate. */
    static final String DUPLICATE_REVIEW = "BANK_TRANSACTION_DUPLICATE_REVIEW";

    private final BankTransactionRepository transactions;
    private final JournalEntryLineRepository lines;
    private final BankReconciliationBankMatchRepository bankMatches;
    private final BankReconciliationGlMatchRepository glMatches;
    private final BankReconciliationOutstandingItemRepository items;
    private final AccountingAuditLogRepository auditLogs;

    /**
     * The bank members of a match (M4), locked: on the account, {@code UNMATCHED}, settled (a {@code PENDING}
     * row is never matchable, D19), dated on or before the window end (inside it or carried in), in no
     * active match other than {@code exceptMatchId} and in no {@code OPEN} item.
     *
     * @throws BankRecException {@code BANK_TRANSACTION_NOT_FOUND} for an id the account does not hold
     * @throws ReconciliationLineIneligibleException 409 for a row not in a matchable state
     */
    public @NonNull List<BankTransaction> lockBankForMatch(
            @NonNull BankReconciliation recon, @NonNull Collection<UUID> ids, @Nullable UUID exceptMatchId) {
        List<BankTransaction> rows = lockBank(recon, ids);
        Set<UUID> inOtherMatches = bankMatches.findByBankTransactionIdInAndActiveTrue(ids).stream()
                .filter(m -> !m.getMatchId().equals(exceptMatchId))
                .map(BankReconciliationBankMatch::getBankTransactionId)
                .collect(Collectors.toSet());
        Set<UUID> inOpenItems = openItemBankIds(ids);
        for (BankTransaction row : rows) {
            UUID id = row.getBankTransactionId();
            if (row.getStatus() != BankTransactionStatus.UNMATCHED) {
                throw ineligible("Bank transaction " + id + " is " + row.getStatus() + ", not UNMATCHED");
            }
            if (row.getSettlementState() == SettlementState.PENDING) {
                throw ineligible("Bank transaction " + id + " is PENDING and never matchable (D19)");
            }
            if (row.getTransactionDate().isAfter(recon.getStatementEndDate())) {
                throw ineligible("Bank transaction " + id + " is dated after the window end "
                        + recon.getStatementEndDate() + "; it belongs to a later window");
            }
            if (inOtherMatches.contains(id)) {
                throw ineligible("Bank transaction " + id + " is already in an active match");
            }
            if (inOpenItems.contains(id)) {
                throw ineligible("Bank transaction " + id + " is in an OPEN outstanding item");
            }
        }
        return rows;
    }

    /** The bank rows, locked, each on the reconciled account; else 404 {@code BANK_TRANSACTION_NOT_FOUND}. */
    public @NonNull List<BankTransaction> lockBank(@NonNull BankReconciliation recon, @NonNull Collection<UUID> ids) {
        Set<UUID> distinct = new HashSet<>(ids);
        List<BankTransaction> rows = transactions.lockByIds(distinct).stream()
                .filter(t -> recon.getGlAccountId().equals(t.getGlAccountId()))
                .sorted(BankRecOrdering.BANK_TRANSACTIONS)
                .toList();
        if (rows.size() != distinct.size()) {
            throw new BankRecException(
                    BankRecErrorCode.BANK_TRANSACTION_NOT_FOUND,
                    "One or more bank transactions were not found on account " + recon.getAccountCode());
        }
        return rows;
    }

    /**
     * The ledger members of a human match (M3), locked: POSTED lines on the reconciled account dated on or
     * before the window end, in no active match other than {@code exceptMatchId}. A line in an {@code OPEN}
     * ledger-side item is matchable — the match clears the item (§5.4).
     *
     * @throws ReconciliationNotFoundException 404 for an unknown line
     * @throws InvalidRequestParameterException 400 for a line on another account
     * @throws ReconciliationLineIneligibleException 409 for a line not in a matchable state
     */
    public @NonNull List<JournalEntryLine> lockLedgerForMatch(
            @NonNull BankReconciliation recon, @NonNull Collection<UUID> ids, @Nullable UUID exceptMatchId) {
        List<JournalEntryLine> locked = lockLedger(recon, ids);
        Set<UUID> inOtherMatches = glMatches.findByGlLineIdInAndActiveTrue(ids).stream()
                .filter(m -> !m.getMatchId().equals(exceptMatchId))
                .map(BankReconciliationGlMatch::getGlLineId)
                .collect(Collectors.toSet());
        for (JournalEntryLine line : locked) {
            if (line.getJournalEntry() == null || line.getJournalEntry().getStatus() != JournalEntryStatus.POSTED) {
                throw ineligible("GL line " + line.getLineId() + " is not on a POSTED entry");
            }
            if (line.getJournalEntry().getTransactionDate().toLocalDate().isAfter(recon.getStatementEndDate())) {
                throw ineligible("GL line " + line.getLineId() + " is dated after the window end "
                        + recon.getStatementEndDate() + "; it belongs to a later window (M3)");
            }
            if (inOtherMatches.contains(line.getLineId())) {
                throw ineligible("GL line " + line.getLineId() + " is already matched in a reconciliation");
            }
        }
        return locked;
    }

    /** The ledger lines, locked, each on the reconciled account. */
    public @NonNull List<JournalEntryLine> lockLedger(
            @NonNull BankReconciliation recon, @NonNull Collection<UUID> ids) {
        Set<UUID> distinct = new HashSet<>(ids);
        List<JournalEntryLine> locked = lines.lockByIds(distinct);
        if (locked.size() != distinct.size()) {
            throw new ReconciliationNotFoundException("One or more GL journal-entry lines were not found");
        }
        for (JournalEntryLine line : locked) {
            if (!recon.getGlAccountId().equals(line.getGlAccountId())) {
                throw new InvalidRequestParameterException("GL line " + line.getLineId()
                        + " does not post to the reconciled account " + recon.getAccountCode());
            }
        }
        return locked.stream()
                .sorted(java.util.Comparator.comparing(JournalEntryLine::getLineId))
                .toList();
    }

    /** Of the given bank transactions, those in an {@code OPEN} item. */
    public @NonNull Set<UUID> openItemBankIds(@NonNull Collection<UUID> bankTransactionIds) {
        return items.findByBankTransactionIdInAndStatus(bankTransactionIds, OutstandingItemStatus.OPEN).stream()
                .map(BankReconciliationOutstandingItem::getBankTransactionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /** Whether any of the rows was a {@code POSSIBLE_DUPLICATE} a duplicate review resolved (M5). */
    public boolean anyFormerPossibleDuplicate(@NonNull Collection<BankTransaction> rows) {
        return rows.stream()
                .anyMatch(row -> auditLogs
                        .findByEntityTypeAndEntityIdOrderByTimestampAsc(
                                BankRecAuditRecorder.BANK_TRANSACTION, row.getBankTransactionId())
                        .stream()
                        .anyMatch(log -> DUPLICATE_REVIEW.equals(log.getOperation())));
    }

    private static ReconciliationLineIneligibleException ineligible(String message) {
        return new ReconciliationLineIneligibleException(message);
    }
}
