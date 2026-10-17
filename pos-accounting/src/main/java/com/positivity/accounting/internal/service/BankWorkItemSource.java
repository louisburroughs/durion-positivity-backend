package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.BankRecPolicy;
import com.positivity.accounting.internal.bankrec.service.CandidateFinder;
import com.positivity.accounting.internal.bankrec.service.CandidateFinder.ScoredLine;
import com.positivity.accounting.internal.dto.WorkItem;
import com.positivity.accounting.internal.dto.WorkItemAction;
import com.positivity.accounting.internal.enums.WorkItemType;
import com.positivity.accounting.internal.security.AccountingPermissions;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The BANK work items (CAP:550 S19, #2515; SPEC-accounting-workspace §4.9, §7.1 "Work items"): the unmatched lines of the
 * reconciliations in progress, and the reconciliations submitted and waiting for an approver. An unmatched credit line
 * for which the matching finds a ledger candidate is reason {@code CARD_DEPOSIT_CANDIDATE}, any other {@code
 * NOT_IN_BOOKS}. Approving is blocked for the preparer unless the tenant allows self-approval (P5).
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankWorkItemSource {

    static final String REASON_CARD_DEPOSIT_CANDIDATE = "CARD_DEPOSIT_CANDIDATE";
    static final String REASON_NOT_IN_BOOKS = "NOT_IN_BOOKS";
    static final String REASON_SUBMITTED = "SUBMITTED";
    static final String APPROVE = "APPROVE";
    static final String BLOCKED_SELF_APPROVAL = "RECONCILIATION_SELF_APPROVAL";

    private final BankReconciliationRepository reconciliations;
    private final BankTransactionRepository transactions;
    private final CandidateFinder candidateFinder;
    private final BankRecPolicy bankRecPolicy;
    private final AccountingCalendarZoneResolver zoneResolver;

    /** The caller's bank items, unordered. */
    public @NonNull List<WorkItem> items() {
        List<WorkItem> items = new ArrayList<>();
        if (WorkItems.has(AccountingPermissions.RECONCILIATION_ADJUST)) {
            items.addAll(unmatchedLines());
        }
        if (WorkItems.has(AccountingPermissions.RECONCILIATION_APPROVE)) {
            items.addAll(awaitingApproval());
        }
        return items;
    }

    private List<WorkItem> unmatchedLines() {
        List<BankReconciliation> inProgress = reconciliations
                .findByStatus(ReconciliationStatus.IN_PROGRESS, Pageable.unpaged())
                .getContent();
        Map<UUID, List<BankReconciliation>> byStatement = new HashMap<>();
        for (BankReconciliation reconciliation : inProgress) {
            if (reconciliation.getStatementId() != null) {
                byStatement
                        .computeIfAbsent(reconciliation.getStatementId(), k -> new ArrayList<>())
                        .add(reconciliation);
            }
        }
        if (byStatement.isEmpty()) {
            return List.of();
        }
        List<BankTransaction> unmatched = transactions.findByStatementIdInAndStatusOrderByTransactionDateAscBankTransactionIdAsc(
                byStatement.keySet(), BankTransactionStatus.UNMATCHED);
        Map<UUID, List<BankTransaction>> rowsByStatement = new HashMap<>();
        for (BankTransaction row : unmatched) {
            rowsByStatement.computeIfAbsent(row.getStatementId(), k -> new ArrayList<>()).add(row);
        }
        List<WorkItem> items = new ArrayList<>();
        Set<UUID> listed = new HashSet<>();
        for (Map.Entry<UUID, List<BankReconciliation>> entry : byStatement.entrySet()) {
            List<BankTransaction> rows = rowsByStatement.getOrDefault(entry.getKey(), List.of());
            if (rows.isEmpty()) {
                continue;
            }
            BankReconciliation reconciliation = entry.getValue().getFirst();
            Map<UUID, List<ScoredLine>> candidates = candidateFinder.rankedForEach(reconciliation, rows);
            for (BankTransaction row : rows) {
                if (!listed.add(row.getBankTransactionId())) {
                    continue;
                }
                boolean credit = row.getSignedAmount() != null && row.getSignedAmount().signum() > 0;
                boolean hasCandidate =
                        !candidates.getOrDefault(row.getBankTransactionId(), List.of()).isEmpty();
                items.add(WorkItems.item(
                        WorkItemType.BANK_LINE_UNMATCHED,
                        row.getBankTransactionId().toString(),
                        WorkItems.references(
                                "account", account(reconciliation),
                                "statementMonth", month(reconciliation),
                                "description", row.getDescription(),
                                "reference", row.getReference()),
                        credit && hasCandidate ? REASON_CARD_DEPOSIT_CANDIDATE : REASON_NOT_IN_BOOKS,
                        row.getSignedAmount(),
                        row.getStatus().name(),
                        row.getTransactionDate(),
                        List.of(),
                        List.of(),
                        WorkItems.routing(
                                "bankTransactionId", row.getBankTransactionId(),
                                "reconciliationId", reconciliation.getReconciliationId(),
                                "statementId", row.getStatementId(),
                                "glAccountId", row.getGlAccountId())));
            }
        }
        return items;
    }

    private List<WorkItem> awaitingApproval() {
        List<BankReconciliation> submitted = reconciliations
                .findByStatus(ReconciliationStatus.SUBMITTED, Pageable.unpaged())
                .getContent();
        if (submitted.isEmpty()) {
            return List.of();
        }
        boolean selfApprovalAllowed = bankRecPolicy.allowSelfApproval();
        String caller = VendorBillDecisions.callerOrNull();
        List<WorkItem> items = new ArrayList<>();
        for (BankReconciliation reconciliation : submitted) {
            boolean preparer = caller != null && caller.equals(reconciliation.getSubmittedBy());
            WorkItemAction approve = preparer && !selfApprovalAllowed
                    ? WorkItemAction.blocked(APPROVE, BLOCKED_SELF_APPROVAL)
                    : WorkItemAction.allowed(APPROVE);
            LocalDate submittedOn = reconciliation.getSubmittedAt() == null
                    ? zoneResolver.today()
                    : zoneResolver.postingDate(reconciliation.getSubmittedAt());
            items.add(WorkItems.item(
                    WorkItemType.RECONCILIATION_AWAITING_APPROVAL,
                    reconciliation.getReconciliationId().toString(),
                    WorkItems.references(
                            "account", account(reconciliation),
                            "statementMonth", month(reconciliation)),
                    REASON_SUBMITTED,
                    reconciliation.getStatementClosingBalance(),
                    reconciliation.getStatus().name(),
                    submittedOn,
                    List.of(approve),
                    List.of(),
                    WorkItems.routing(
                            "reconciliationId", reconciliation.getReconciliationId(),
                            "statementId", reconciliation.getStatementId())));
        }
        return items;
    }

    private static @Nullable String account(BankReconciliation reconciliation) {
        if (reconciliation.getAccountCode() == null) {
            return reconciliation.getAccountName();
        }
        return reconciliation.getAccountName() == null
                ? reconciliation.getAccountCode()
                : reconciliation.getAccountCode() + " " + reconciliation.getAccountName();
    }

    private static @Nullable String month(BankReconciliation reconciliation) {
        return reconciliation.getStatementEndDate() == null
                ? null
                : YearMonth.from(reconciliation.getStatementEndDate()).toString();
    }
}
