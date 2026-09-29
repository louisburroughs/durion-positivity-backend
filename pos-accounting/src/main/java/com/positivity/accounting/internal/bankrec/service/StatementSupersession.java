package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.InvalidationReason;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemSide;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.intake.Justification;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Statement supersession by a corrected re-import (SPEC-manual-bank-reconciliation §4.9 path 3, R2, O2, D15;
 * story S5, #2304). The intake runs {@link #requireEligible} before it writes anything, {@link #retire} before
 * it commits the corrected statement (so U1, U2 and the collision check leave the old one out), and {@link
 * #link} once the corrected statement has its id. The file adapter runs {@link #requireEligible} at upload too.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StatementSupersession {

    /** {@code exclusionReason} of a superseded statement's rows (R2), and the release reason of its bank items. */
    public static final String STATEMENT_SUPERSEDED = "STATEMENT_SUPERSEDED";

    private static final Set<ReconciliationStatus> ACTIVE =
            EnumSet.of(ReconciliationStatus.IN_PROGRESS, ReconciliationStatus.SUBMITTED);
    private static final Set<ReconciliationStatus> APPROVED =
            EnumSet.of(ReconciliationStatus.FINALIZED, ReconciliationStatus.INVALIDATED);
    private static final Set<BankTransactionStatus> LEFT_ALONE =
            EnumSet.of(BankTransactionStatus.EXCLUDED, BankTransactionStatus.REMOVED_BY_SOURCE);

    private final Clock clock;
    private final BankStatementRepository statements;
    private final BankTransactionRepository transactions;
    private final BankReconciliationRepository reconciliations;
    private final BankReconciliationOutstandingItemRepository items;
    private final ReconciliationLifecycle lifecycle;
    private final BankRecAuditRecorder audit;

    /** What a supersession names: the statement it retires and the justification, trimmed. */
    public record Request(
            @NonNull BankStatement superseded, @NonNull String justification) {}

    /**
     * Checks a supersession before anything is written, in the §4.9 path 3 order: the justification ({@code
     * VALIDATION_ERROR} absent or blank, {@code JUSTIFICATION_REQUIRED} for 1–9 characters); the named statement
     * known in the tenant, on the account and {@code COMMITTED} (else 422 {@code
     * STATEMENT_SUPERSESSION_NOT_ELIGIBLE}); no {@code IN_PROGRESS} or {@code SUBMITTED} reconciliation of it
     * (else 409 {@code RECONCILIATION_WINDOW_ALREADY_RECONCILED}). A justification without a statement is a
     * {@code VALIDATION_ERROR}.
     *
     * @return the request, or null when {@code supersedesStatementId} is absent
     */
    public @Nullable Request requireEligible(
            @NonNull UUID glAccountId, @Nullable UUID supersedesStatementId, @Nullable String justification) {
        if (supersedesStatementId == null) {
            if (justification != null) {
                throw BankRecException.field(
                        BankRecErrorCode.VALIDATION_ERROR,
                        "supersessionJustification is only accepted with supersedesStatementId",
                        "supersessionJustification",
                        "requires supersedesStatementId");
            }
            return null;
        }
        String reason = Justification.required(justification, "supersessionJustification");
        BankStatement superseded = statements
                .findById(supersedesStatementId)
                .filter(s -> s.getGlAccountId().equals(glAccountId))
                .filter(s -> s.getStatus() == BankStatementStatus.COMMITTED)
                .orElseThrow(() -> BankRecException.field(
                        BankRecErrorCode.STATEMENT_SUPERSESSION_NOT_ELIGIBLE,
                        "Statement " + supersedesStatementId + " is not a COMMITTED statement of this account",
                        "supersedesStatementId",
                        "must name a COMMITTED statement of the same account"));
        reconciliations.findByStatementIdAndStatusIn(supersedesStatementId, ACTIVE).stream()
                .findFirst()
                .ifPresent(active -> {
                    throw BankRecException.field(
                            BankRecErrorCode.RECONCILIATION_WINDOW_ALREADY_RECONCILED,
                            "Statement " + supersedesStatementId + " has reconciliation "
                                    + active.getReconciliationId() + " (" + active.getStatus()
                                    + "); finish or cancel it before superseding the statement",
                            "reconciliationId",
                            active.getReconciliationId().toString());
                });
        return new Request(superseded, reason);
    }

    /**
     * Retires the superseded statement before the corrected one is committed: the statement {@code
     * SUPERSEDED} (flushed, so the U1 unique and the U2 exclusion constraint leave it out); a {@code FINALIZED}
     * reconciliation of it {@code INVALIDATED} ({@code STATEMENT_SUPERSEDED}) and the members of it and of an
     * already {@code INVALIDATED} one released; bank-side {@code OPEN} items on its rows {@code RELEASED}; its rows
     * {@code EXCLUDED} with {@code STATEMENT_SUPERSEDED} (R2 — they raise no collision with the corrected rows).
     * Ledger-side items stay (O2).
     */
    public void retire(@NonNull Request request, @NonNull String actor) {
        BankStatement old = request.superseded();
        Instant now = Instant.now(clock);
        old.setStatus(BankStatementStatus.SUPERSEDED);
        statements.saveAndFlush(old);

        for (BankReconciliation recon : reconciliations.findByStatementIdAndStatusIn(old.getStatementId(), APPROVED)) {
            lifecycle.invalidate(recon, InvalidationReason.STATEMENT_SUPERSEDED, null, actor);
            lifecycle.releaseMembers(recon);
        }

        List<BankTransaction> rows = transactions.findByStatementIdOrderBySourceRowNumberAsc(old.getStatementId());
        List<UUID> rowIds =
                rows.stream().map(BankTransaction::getBankTransactionId).toList();
        List<BankReconciliationOutstandingItem> bankItems = rowIds.isEmpty()
                ? List.of()
                : items.findByBankTransactionIdInAndStatus(rowIds, OutstandingItemStatus.OPEN).stream()
                        .filter(i -> i.getSide() == OutstandingItemSide.BANK)
                        .toList();
        for (BankReconciliationOutstandingItem item : bankItems) {
            item.setStatus(OutstandingItemStatus.RELEASED);
            item.setReleasedAt(now);
            item.setReleasedBy(actor);
            item.setReleaseReason(STATEMENT_SUPERSEDED);
        }
        items.saveAll(bankItems);

        int excluded = 0;
        for (BankTransaction row : rows) {
            if (LEFT_ALONE.contains(row.getStatus())) {
                continue;
            }
            row.setStatus(BankTransactionStatus.EXCLUDED);
            row.setExclusionReason(STATEMENT_SUPERSEDED);
            row.setExcludedBy(actor);
            row.setExcludedAt(now);
            excluded++;
        }
        transactions.saveAllAndFlush(rows);
        log.info(
                "Statement {} superseded: {} row(s) excluded, {} bank item(s) released",
                old.getStatementId(),
                excluded,
                bankItems.size());
    }

    /**
     * Links the retired statement to the corrected one and writes the one {@code BANK_STATEMENT_SUPERSEDE} audit
     * row ({@code COMMITTED} → {@code SUPERSEDED}, the justification, the request's trace id).
     */
    public void link(@NonNull Request request, @NonNull UUID correctedStatementId, @NonNull String actor) {
        BankStatement old = request.superseded();
        old.setSupersededByStatementId(correctedStatementId);
        statements.save(old);
        audit.record(
                BankRecAuditRecorder.BANK_STATEMENT,
                old.getStatementId(),
                BankRecAuditRecorder.BANK_STATEMENT_SUPERSEDE,
                actor,
                request.justification(),
                BankStatementStatus.COMMITTED.name(),
                BankStatementStatus.SUPERSEDED.name());
    }
}
