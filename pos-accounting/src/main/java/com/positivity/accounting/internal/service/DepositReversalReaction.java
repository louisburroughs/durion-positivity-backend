package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.Deposit;
import com.positivity.accounting.internal.entity.DepositSession;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.UndepositedSession;
import com.positivity.accounting.internal.enums.DepositStatus;
import com.positivity.accounting.internal.enums.UndepositedSessionStatus;
import com.positivity.accounting.internal.event.LedgerReversalApplied;
import com.positivity.accounting.internal.exception.CashSetupException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.DepositRepository;
import com.positivity.accounting.internal.repository.DepositSessionRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.UndepositedSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A bank deposit's entry reversed (CAP:550 S18, #2514; SPEC-accounting-workspace §4.5; ADR-0047), by Reverse deposit
 * or through {@code POST /v1/accounting/journal-entries/{id}/reverse}: the deposit becomes {@code REVERSED}, its
 * sessions return to {@code UNDEPOSITED} (1090 and 1095 are restored by the reversal entry itself), an audit row names
 * the actor and {@code accounting.deposit.recorded} is queued again with {@code status = REVERSED}. Either way the
 * deposit record and the ledger never disagree.
 *
 * <p>A deposit's <em>reversal</em> entry is never reversed itself: 409 {@code DEPOSIT_REVERSAL_NOT_REVERSIBLE}, and
 * the whole reversal rolls back. Reversing it would post the deposit's lines again (Dr bank / Cr 1090 / Dr or Cr 1095)
 * while the deposit stays {@code REVERSED} and its sessions wait to be deposited, so the bank would be debited twice
 * once they are; a deposit is corrected by recording it again (ADR-0047).
 *
 * <p>It hears the in-process event {@code JournalEntryServiceImpl} publishes after a reversal and runs in that
 * transaction, as {@link RegisterFloatReversalReaction} does. It never gates on location: the endpoint that reversed
 * the entry already decided who may. Locks: those of the reversal (the entry-number sequence, then the entry in {@code
 * markReversed}), then the deposit, then its sessions; Reverse deposit takes no lock before the reversal, so both
 * routes share this order ({@link DepositServiceImpl#reverse}).
 *
 * <p>Reverse deposit hands its {@code requestId} and body hash over {@link #underRequest} (a resource bound to the
 * transaction's thread for the length of the reversal), so the deposit, its audit row and the replay all carry them; a
 * reversal through the journal-entry endpoint has none.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepositReversalReaction {

    static final String AUDIT_REVERSE = "BANK_DEPOSIT_REVERSE";

    private static final int REASON_MAX = 1000;

    /** The key the Reverse deposit command's request is bound under for the length of its reversal. */
    private static final String REQUEST_RESOURCE = DepositReversalReaction.class.getName() + ".request";

    /** The Reverse deposit command reversing {@code depositId}: its idempotency key and body hash. */
    record ReversalRequest(
            @NonNull UUID depositId,
            @NonNull UUID requestId,
            @NonNull String requestHash) {}

    /** Runs {@code reversal} with {@code request} bound, for this reaction to stamp on the deposit it reverses. */
    static <T> T underRequest(@NonNull ReversalRequest request, @NonNull Supplier<T> reversal) {
        TransactionSynchronizationManager.bindResource(REQUEST_RESOURCE, request);
        try {
            return reversal.get();
        } finally {
            TransactionSynchronizationManager.unbindResourceIfPossible(REQUEST_RESOURCE);
        }
    }

    private final DepositRepository deposits;
    private final DepositSessionRepository depositSessions;
    private final UndepositedSessionRepository sessions;
    private final JournalEntryRepository journalEntries;
    private final AccountingAuditLogRepository auditLogs;
    private final DepositFacts facts;
    private final Clock clock;

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onReversed(@NonNull LedgerReversalApplied reversed) {
        deposits.lockByReversalJournalEntryId(reversed.originalJournalEntryId()).ifPresent(deposit -> {
            log.warn(
                    "Refused the reversal of bank deposit {} reversal entry {}",
                    deposit.getDepositId(),
                    reversed.originalJournalEntryId());
            throw new CashSetupException(
                    CashSetupException.Code.DEPOSIT_REVERSAL_NOT_REVERSIBLE,
                    "Journal entry " + deposit.getReversalJournalEntryNumber() + " reverses bank deposit "
                            + deposit.getJournalEntryNumber() + " and is never reversed itself; record the deposit"
                            + " again instead",
                    deposit.getDepositId().toString(),
                    "Record the deposit again");
        });
        deposits.lockByJournalEntryId(reversed.originalJournalEntryId())
                .filter(deposit -> deposit.getStatus() == DepositStatus.RECORDED)
                .ifPresent(deposit -> reverse(deposit, reversed));
    }

    private void reverse(Deposit deposit, LedgerReversalApplied reversed) {
        deposit.setStatus(DepositStatus.REVERSED);
        deposit.setReversalJournalEntryId(reversed.reversalJournalEntryId());
        deposit.setReversalJournalEntryNumber(journalEntries
                .findById(reversed.reversalJournalEntryId())
                .map(JournalEntry::getEntryNumber)
                .orElse(null));
        deposit.setReversalDate(reversed.reversalDate());
        String reason = reversed.reversalReason().trim();
        deposit.setReversalReason(reason.length() <= REASON_MAX ? reason : reason.substring(0, REASON_MAX));
        deposit.setReversalOverrideJustification(reversed.overrideJustification());
        deposit.setReversedBy(reversed.actor());
        deposit.setReversedAt(Instant.now(clock));
        if (TransactionSynchronizationManager.getResource(REQUEST_RESOURCE) instanceof ReversalRequest request
                && request.depositId().equals(deposit.getDepositId())) {
            deposit.setReversalRequestId(request.requestId());
            deposit.setReversalRequestHash(request.requestHash());
        }
        Deposit saved = deposits.saveAndFlush(deposit);

        List<UndepositedSession> returned = sessions.lockByDepositId(saved.getDepositId());
        returned.forEach(session -> {
            session.setStatus(UndepositedSessionStatus.UNDEPOSITED);
            session.setDepositId(null);
        });
        sessions.saveAllAndFlush(returned);

        List<DepositSession> taken =
                depositSessions.findByDepositIdOrderByClosedAtAscSessionIdAsc(saved.getDepositId());
        AccountingAuditLog audit = new AccountingAuditLog();
        audit.setEntityType(DepositServiceImpl.AUDIT_ENTITY_TYPE);
        audit.setEntityId(saved.getDepositId());
        audit.setOperation(AUDIT_REVERSE);
        audit.setUserId(reversed.actor());
        audit.setJustification(saved.getReversalReason());
        audit.setOldValue("status=RECORDED;journalEntryId=" + saved.getJournalEntryId());
        audit.setNewValue("status=REVERSED;reversalJournalEntryId=" + saved.getReversalJournalEntryId()
                + ";reversalDate=" + saved.getReversalDate() + ";amount="
                + saved.getAmount().toPlainString()
                + ";currencyCode=" + saved.getCurrencyCode() + ";sessions="
                + taken.stream().map(DepositSession::getSessionId).toList() + ";requestId="
                + saved.getReversalRequestId() + ";overrideJustification=" + saved.getReversalOverrideJustification());
        auditLogs.save(audit);
        facts.changed(saved, taken, reversed.actor());
        log.info(
                "Bank deposit {} ({}) reversed by {} with {} on {}: {} session(s) wait to be deposited again",
                saved.getDepositId(),
                saved.getJournalEntryNumber(),
                saved.getReversedBy(),
                saved.getReversalJournalEntryNumber(),
                saved.getReversalDate(),
                returned.size());
    }
}
