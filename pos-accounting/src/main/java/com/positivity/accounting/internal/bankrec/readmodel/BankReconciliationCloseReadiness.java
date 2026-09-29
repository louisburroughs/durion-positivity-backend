package com.positivity.accounting.internal.bankrec.readmodel;

import com.positivity.accounting.internal.bankrec.dto.CloseReadinessAccount;
import com.positivity.accounting.internal.bankrec.dto.CloseReadinessCheck;
import com.positivity.accounting.internal.bankrec.dto.CloseReadinessOutstandingItem;
import com.positivity.accounting.internal.bankrec.dto.CloseReadinessResponse;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationAdjustment;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.AdjustmentStatus;
import com.positivity.accounting.internal.bankrec.enums.BankAdjustmentType;
import com.positivity.accounting.internal.bankrec.enums.BankRecClosePolicy;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.enums.ReadinessCheckCode;
import com.positivity.accounting.internal.bankrec.enums.ReadinessSeverity;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.intake.IncompleteImportLookup;
import com.positivity.accounting.internal.bankrec.intake.Justification;
import com.positivity.accounting.internal.bankrec.repository.AccountDate;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationAdjustmentRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.BankCashAccounts;
import com.positivity.accounting.internal.bankrec.service.BankCashAccounts.BankCashAccount;
import com.positivity.accounting.internal.bankrec.service.BankRecPolicy;
import com.positivity.accounting.internal.bankrec.service.BankRecSettings;
import com.positivity.accounting.internal.bankrec.service.FunctionalCurrency;
import com.positivity.accounting.internal.bankrec.service.LedgerEntries;
import com.positivity.accounting.internal.bankrec.service.LedgerLine;
import com.positivity.accounting.internal.bankrec.service.ReconciliationCalculator;
import com.positivity.accounting.internal.bankrec.service.ReconciliationCalculator.Unexplained;
import com.positivity.accounting.internal.bankrec.service.ReconciliationChain;
import com.positivity.accounting.internal.bankrec.service.ReconciliationLedger;
import com.positivity.accounting.internal.dto.BankReconciliationExceptionRequest;
import com.positivity.accounting.internal.entity.AccountingPeriod;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.exception.PeriodBankReconciliationIncompleteException;
import com.positivity.accounting.internal.exception.PeriodBankReconciliationIncompleteException.UnreconciledAccount;
import com.positivity.accounting.internal.exception.PeriodCloseExceptionNotPermittedException;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.AccountingPeriodGate;
import com.positivity.security.common.SecurityContextHelper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bank reconciliation close-readiness read model (SPEC-manual-bank-reconciliation §5.2, §5.3, §5.8, §5.9;
 * story S6, #2305): the §5.3 checks per in-scope account plus the tenant-wide checks, derived on every call and
 * never persisted, and the close decision the policy draws from them. It only reads: every posting still goes
 * through {@code AccountingPeriodGate} (§5.9).
 *
 * <p>Per account, with {@code E} the period end and {@code T = E − lag}:
 *
 * <ul>
 *   <li>the <b>baseline that applies at {@code E}</b> is the start of the latest COMMITTED acknowledged statement
 *       starting on/before {@code E} (§3.1) — the lower bound of the {@code UNEXPLAINED_*} checks, which are not
 *       evaluated without one;
 *   <li>{@code coverageFrontier} is the latest COMMITTED statement end, {@code reconciledFrontier} the end of the
 *       contiguous FINALIZED chain from that baseline (§4.1); the <b>covering</b> reconciliation is the last chain
 *       member starting on/before {@code E}.
 * </ul>
 *
 * <p>Outstanding-item age is measured at the earlier of {@code E} and today (the shared {@link Clock}, ADR-0024),
 * so a period still running is not aged against its future end.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankReconciliationCloseReadiness {

    /** How many ids a reference list carries (§5.3 "first 50"). */
    static final int REFERENCE_LIMIT = 50;

    /** The longest exception justification the audit row keeps (the request's documented maximum). */
    static final int MAX_JUSTIFICATION = 1000;

    private static final JsonMapper SNAPSHOT_MAPPER = JsonMapper.builder().build();

    private static final List<ReconciliationStatus> IN_FLIGHT =
            List.of(ReconciliationStatus.IN_PROGRESS, ReconciliationStatus.SUBMITTED);
    private static final List<BankTransactionStatus> UNRESOLVED =
            List.of(BankTransactionStatus.UNMATCHED, BankTransactionStatus.POSSIBLE_DUPLICATE);

    private final Clock clock;
    private final BankRecPolicy policy;
    private final BankRecSettings settings;
    private final FunctionalCurrency currency;
    private final BankCashAccounts bankCashAccounts;
    private final BankStatementRepository statements;
    private final BankReconciliationRepository reconciliations;
    private final BankTransactionRepository transactions;
    private final BankReconciliationOutstandingItemRepository outstandingItems;
    private final BankReconciliationAdjustmentRepository adjustments;
    private final ReconciliationCalculator calculator;
    private final ReconciliationLedger ledger;
    private final LedgerEntries ledgerEntries;
    private final ObjectProvider<IncompleteImportLookup> importLookups;

    /**
     * The close decision: whether readiness held, and whether an exception was granted to close anyway.
     *
     * @param justification the granted exception's trimmed justification; null when none was granted
     */
    public record CloseDecision(
            boolean bankReconciliationReady,
            boolean exceptionGranted,
            @Nullable String justification) {

        public CloseDecision(boolean bankReconciliationReady, boolean exceptionGranted) {
            this(bankReconciliationReady, exceptionGranted, null);
        }
    }

    /** Readiness of {@code period} for a read (§5.3): no row is locked. */
    public @NonNull CloseReadinessResponse evaluate(@NonNull AccountingPeriod period) {
        return evaluate(period, false);
    }

    /**
     * Readiness of {@code period}.
     *
     * @param lockCovering whether to row-lock each covering reconciliation before its balance is compared (the
     *     close command, §6.3, I3): an approval racing the close serializes on that row
     */
    public @NonNull CloseReadinessResponse evaluate(@NonNull AccountingPeriod period, boolean lockCovering) {
        BankRecPolicy.Settings effective = policy.settings();
        LocalDate start = period.getStartDate();
        LocalDate end = period.getEndDate();
        int lag = effective.closeCoverageLagDays();

        List<CloseReadinessCheck> tenantChecks = new ArrayList<>();
        draftEntries(start, end).ifPresent(tenantChecks::add);
        tenantChecks.addAll(clearingBalanceAging(end));

        List<BankCashAccount> inScope = bankCashAccounts.listInScope(effective.closeScope());
        Map<UUID, LocalDate> coverage = new HashMap<>();
        if (!inScope.isEmpty()) {
            for (AccountDate row : statements.findLatestEndDateByGlAccountIdIn(
                    inScope.stream().map(BankCashAccount::glAccountId).toList(), BankStatementStatus.COMMITTED)) {
                coverage.put(row.glAccountId(), row.date());
            }
        }
        List<CloseReadinessAccount> accounts = new ArrayList<>();
        for (BankCashAccount account : inScope) {
            accounts.add(evaluateAccount(period, account, coverage.get(account.glAccountId()), lag, lockCovering));
        }

        int blocking = count(tenantChecks, accounts, ReadinessSeverity.BLOCKING);
        int warning = count(tenantChecks, accounts, ReadinessSeverity.WARNING);
        boolean draftsBlock = tenantChecks.stream().anyMatch(c -> c.code() == ReadinessCheckCode.DRAFT_JOURNAL_ENTRIES);
        boolean ready = effective.closePolicy().blocksClose() ? blocking == 0 : !draftsBlock;
        return new CloseReadinessResponse(
                period.getPeriodCode(),
                start,
                end,
                period.getStatus(),
                effective.closePolicy(),
                lag,
                ready,
                blocking,
                warning,
                List.copyOf(tenantChecks),
                List.copyOf(accounts));
    }

    /**
     * The policy's verdict on a close (§5.2, §5.9, I5), after the DRAFT check has passed.
     *
     * <ul>
     *   <li>no BLOCKING bank reconciliation check: close, the exception body (if any) is not used;
     *   <li>{@code ADVISORY}: close, not ready, the exception body is ignored;
     *   <li>{@code REQUIRED}: 422 {@code PERIOD_BANK_RECONCILIATION_INCOMPLETE}; an exception body is reported as
     *       refused by policy;
     *   <li>{@code REQUIRED_WITH_EXCEPTION}: without an exception 422; with one from a caller lacking {@code
     *       close} or {@code override} 403 {@code PERIOD_CLOSE_EXCEPTION_NOT_PERMITTED}; with a justification
     *       under 10 characters 400 {@code JUSTIFICATION_REQUIRED}; otherwise close with the exception granted.
     * </ul>
     *
     * @param exception the close body's {@code bankReconciliationException}; null without one
     */
    public @NonNull CloseDecision decide(
            @NonNull CloseReadinessResponse readiness, @Nullable BankReconciliationExceptionRequest exception) {
        List<UnreconciledAccount> unreconciled = unreconciled(readiness);
        if (unreconciled.isEmpty()) {
            return new CloseDecision(true, false);
        }
        BankRecClosePolicy closePolicy = readiness.policy();
        if (!closePolicy.blocksClose()) {
            return new CloseDecision(false, false);
        }
        if (exception == null) {
            throw new PeriodBankReconciliationIncompleteException(readiness.periodCode(), unreconciled, null);
        }
        if (closePolicy == BankRecClosePolicy.REQUIRED) {
            throw new PeriodBankReconciliationIncompleteException(
                    readiness.periodCode(), unreconciled, "not permitted by policy " + BankRecClosePolicy.REQUIRED);
        }
        if (!holds(AccountingPermissions.PERIOD_CLOSE) || !holds(AccountingPeriodGate.OVERRIDE_AUTHORITY)) {
            throw new PeriodCloseExceptionNotPermittedException("A bank reconciliation close exception needs both "
                    + AccountingPermissions.PERIOD_CLOSE + " and " + AccountingPeriodGate.OVERRIDE_AUTHORITY);
        }
        String justification =
                Justification.required(exception.getJustification(), "bankReconciliationException.justification");
        if (justification.length() > MAX_JUSTIFICATION) {
            throw BankRecException.field(
                    BankRecErrorCode.VALIDATION_ERROR,
                    "bankReconciliationException.justification must not exceed " + MAX_JUSTIFICATION + " characters",
                    "bankReconciliationException.justification",
                    "at most " + MAX_JUSTIFICATION + " characters");
        }
        return new CloseDecision(false, true, justification);
    }

    /** The full readiness as JSON: the snapshot the {@code PERIOD_CLOSE_BANKREC_EXCEPTION} audit row keeps (I5). */
    public static @NonNull String snapshot(@NonNull CloseReadinessResponse readiness) {
        return SNAPSHOT_MAPPER.writeValueAsString(readiness);
    }

    /**
     * A one-line readiness summary for the {@code PERIOD_CLOSE} audit row (§5.9), e.g. {@code
     * policy=REQUIRED_WITH_EXCEPTION;ready=false;blocking=1;warning=0;unreconciled=1000:RECONCILIATION_IN_FLIGHT}.
     */
    public static @NonNull String summary(@NonNull CloseReadinessResponse readiness) {
        String unreconciled = unreconciled(readiness).stream()
                .map(a -> a.accountCode() + ":" + String.join("|", a.checkCodes()))
                .collect(Collectors.joining(","));
        return "policy=" + readiness.policy() + ";ready=" + readiness.ready() + ";blocking=" + readiness.blockingCount()
                + ";warning=" + readiness.warningCount() + ";unreconciled=" + unreconciled;
    }

    /** The accounts with a BLOCKING check, each with those checks' codes, in account order. */
    static @NonNull List<UnreconciledAccount> unreconciled(@NonNull CloseReadinessResponse readiness) {
        List<UnreconciledAccount> blocked = new ArrayList<>();
        for (CloseReadinessAccount account : readiness.accounts()) {
            List<String> codes = account.checks().stream()
                    .filter(c -> c.severity() == ReadinessSeverity.BLOCKING)
                    .map(c -> c.code().name())
                    .toList();
            if (!codes.isEmpty()) {
                blocked.add(new UnreconciledAccount(account.glAccountId(), account.accountCode(), codes));
            }
        }
        return blocked;
    }

    // ---- per account ------------------------------------------------------------------------------------

    private CloseReadinessAccount evaluateAccount(
            AccountingPeriod period,
            BankCashAccount account,
            @Nullable LocalDate coverageFrontier,
            int lag,
            boolean lockCovering) {
        UUID id = account.glAccountId();
        LocalDate start = period.getStartDate();
        LocalDate end = period.getEndDate();
        LocalDate threshold = end.minusDays(lag);
        List<CloseReadinessCheck> checks = new ArrayList<>();

        LocalDate baselineDate = statements
                .findFirstByGlAccountIdAndStatusAndGapAcknowledgementIsNotNullAndStartDateLessThanEqualOrderByStartDateDesc(
                        id, BankStatementStatus.COMMITTED, end)
                .map(BankStatement::getStartDate)
                .orElse(null);
        List<BankReconciliation> chain = ReconciliationChain.members(
                reconciliations.findByGlAccount_GlAccountIdAndStatusOrderByStatementStartDateAsc(
                        id, ReconciliationStatus.FINALIZED),
                baselineDate);
        LocalDate reconciledFrontier = chain.isEmpty() ? null : chain.getLast().getStatementEndDate();
        BankReconciliation covering = chain.stream()
                .filter(r -> !r.getStatementStartDate().isAfter(end))
                .reduce((first, second) -> second)
                .orElse(null);
        if (covering != null && lockCovering) {
            covering = reconciliations.lockById(covering.getReconciliationId()).orElse(covering);
        }

        if (coverageFrontier == null || coverageFrontier.isBefore(threshold)) {
            Map<String, Object> refs = refs("glAccountId", id, "coverageFrontier", coverageFrontier);
            refs.put("requiredThrough", threshold);
            checks.add(check(
                    ReadinessCheckCode.STATEMENT_COVERAGE,
                    coverageFrontier == null
                            ? "No COMMITTED statement covers the account; one must reach " + threshold
                            : "Statements reach " + coverageFrontier + "; they must reach " + threshold,
                    refs));
        }
        // An account without any COMMITTED statement is reported by STATEMENT_COVERAGE alone (§5.3, §8.4): with no
        // statement there is nothing to reconcile yet.
        if (coverageFrontier != null && (reconciledFrontier == null || reconciledFrontier.isBefore(threshold))) {
            Map<String, Object> refs = refs("glAccountId", id, "reconciledFrontier", reconciledFrontier);
            refs.put("reconciliationId", covering != null ? covering.getReconciliationId() : null);
            refs.put("requiredThrough", threshold);
            checks.add(check(
                    ReadinessCheckCode.RECONCILIATION_APPROVED,
                    reconciledFrontier == null
                            ? "No FINALIZED reconciliation chain from the baseline; it must reach " + threshold
                            : "FINALIZED reconciliations reach " + reconciledFrontier + "; they must reach "
                                    + threshold,
                    refs));
        }
        inFlight(id, end).ifPresent(checks::add);
        invalidated(id, start, end).ifPresent(checks::add);
        if (covering != null) {
            balanceAgreement(id, covering).ifPresent(checks::add);
        }
        if (baselineDate != null) {
            Unexplained unexplained = calculator.unexplained(
                    id, baselineDate, end, covering != null ? covering.getReconciliationId() : null);
            unexplainedChecks(id, baselineDate, unexplained, checks);
        }
        unpostedAdjustments(id, end).ifPresent(checks::add);
        if (lag > 0
                && reconciledFrontier != null
                && !reconciledFrontier.isBefore(threshold)
                && reconciledFrontier.isBefore(end)) {
            Map<String, Object> refs = refs("glAccountId", id, "reconciledFrontier", reconciledFrontier);
            refs.put("lagDays", lag);
            checks.add(check(
                    ReadinessCheckCode.COVERAGE_LAG_APPLIED,
                    "Reconciled only to " + reconciledFrontier + "; accepted because of the " + lag
                            + "-day coverage lag",
                    refs));
        }
        incompleteImports(id, end).ifPresent(checks::add);

        LocalDate agingDay = earlier(end, LocalDate.now(clock));
        List<BankReconciliationOutstandingItem> open =
                outstandingItems.findByGlAccountIdAndItemDateLessThanEqual(id, end).stream()
                        .filter(i -> i.getStatus() == OutstandingItemStatus.OPEN)
                        .sorted(Comparator.comparing(BankReconciliationOutstandingItem::getItemDate)
                                .thenComparing(BankReconciliationOutstandingItem::getOutstandingItemId))
                        .toList();
        outstandingAging(open, agingDay).ifPresent(checks::add);
        lateBankTransactions(id, start, end).ifPresent(checks::add);
        if (covering != null) {
            reconciledAfterClose(period, covering).ifPresent(checks::add);
        }

        List<CloseReadinessOutstandingItem> items = open.stream()
                .map(i -> new CloseReadinessOutstandingItem(
                        i.getOutstandingItemId(),
                        i.getSide(),
                        i.getItemKind(),
                        i.getItemDate(),
                        i.getSignedAmount(),
                        Math.max(0, ChronoUnit.DAYS.between(i.getItemDate(), agingDay))))
                .toList();
        BigDecimal itemSum = open.stream()
                .map(BankReconciliationOutstandingItem::getSignedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CloseReadinessAccount(
                id,
                account.accountCode(),
                account.accountName(),
                baselineDate,
                coverageFrontier,
                reconciledFrontier,
                items,
                itemSum,
                List.copyOf(checks));
    }

    private Optional<CloseReadinessCheck> inFlight(UUID account, LocalDate end) {
        List<BankReconciliation> rows =
                reconciliations
                        .findByGlAccount_GlAccountIdAndStatusInAndStatementEndDateLessThanEqualOrderByStatementStartDateAsc(
                                account, IN_FLIGHT, end);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(check(
                ReadinessCheckCode.RECONCILIATION_IN_FLIGHT,
                rows.size() + " IN_PROGRESS or SUBMITTED reconciliation(s) end on or before " + end,
                refs("reconciliationIds", ids(rows.stream().map(BankReconciliation::getReconciliationId)))));
    }

    private Optional<CloseReadinessCheck> invalidated(UUID account, LocalDate start, LocalDate end) {
        List<BankReconciliation> rows =
                reconciliations.findIntersecting(account, ReconciliationStatus.INVALIDATED, start, end);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> refs =
                refs("reconciliationIds", ids(rows.stream().map(BankReconciliation::getReconciliationId)));
        refs.put(
                "invalidationReasons",
                rows.stream()
                        .map(BankReconciliation::getInvalidationReason)
                        .distinct()
                        .toList());
        return Optional.of(check(
                ReadinessCheckCode.RECONCILIATION_INVALIDATED,
                rows.size() + " INVALIDATED reconciliation(s) intersect the period without a FINALIZED successor",
                refs));
    }

    /** §5.3 {@code BALANCE_AGREEMENT}: belt and braces over S5's invalidation hook (I1). */
    private Optional<CloseReadinessCheck> balanceAgreement(UUID account, BankReconciliation covering) {
        BigDecimal approved = covering.getApprovedGlEndingBalance();
        BigDecimal live = ledger.balanceAsOf(account, covering.getStatementEndDate());
        if (approved != null && approved.compareTo(live) == 0) {
            return Optional.empty();
        }
        Map<String, Object> refs = refs("glAccountId", account, "reconciliationId", covering.getReconciliationId());
        refs.put("statementEndDate", covering.getStatementEndDate());
        refs.put("approvedGlEndingBalance", approved);
        refs.put("liveGlBalance", live);
        return Optional.of(check(
                ReadinessCheckCode.BALANCE_AGREEMENT,
                "The ledger balance at " + covering.getStatementEndDate() + " is " + currency.display(live)
                        + " but the covering reconciliation approved "
                        + (approved != null ? currency.display(approved) : "no balance"),
                refs));
    }

    private void unexplainedChecks(
            UUID account, LocalDate baselineDate, Unexplained unexplained, List<CloseReadinessCheck> checks) {
        if (unexplained.countBank() > 0) {
            Map<String, Object> refs = refs("count", unexplained.countBank(), "sum", unexplained.sumBank());
            refs.put("baselineDate", baselineDate);
            refs.put("bankTransactionIds", ids(unexplained.bank().stream().map(BankTransaction::getBankTransactionId)));
            checks.add(check(
                    ReadinessCheckCode.UNEXPLAINED_BANK_TRANSACTIONS,
                    unexplained.countBank() + " unexplained bank transaction(s) from " + baselineDate,
                    refs));
        }
        if (unexplained.countLedger() > 0) {
            Map<String, Object> refs = refs("count", unexplained.countLedger(), "sum", unexplained.sumLedger());
            refs.put("baselineDate", baselineDate);
            refs.put(
                    "glLineIds",
                    ids(Stream.concat(
                            unexplained.ledger().stream().map(LedgerLine::lineId),
                            unexplained.agedAwaitingReaffirmation().stream()
                                    .map(BankReconciliationOutstandingItem::getGlLineId)
                                    .filter(Objects::nonNull))));
            checks.add(check(
                    ReadinessCheckCode.UNEXPLAINED_LEDGER_LINES,
                    unexplained.countLedger() + " unexplained ledger line(s) from " + baselineDate,
                    refs));
        }
    }

    /** §5.3 {@code UNPOSTED_ADJUSTMENTS}: a POSTED adjustment whose entry is missing or not POSTED (defensive). */
    private Optional<CloseReadinessCheck> unpostedAdjustments(UUID account, LocalDate end) {
        List<BankReconciliationAdjustment> posted = adjustments.findAllOnAccount(account).stream()
                .filter(a -> a.getStatus() == AdjustmentStatus.POSTED)
                .filter(a -> a.getTransactionDate() == null
                        || !a.getTransactionDate().isAfter(end))
                .toList();
        if (posted.isEmpty()) {
            return Optional.empty();
        }
        Set<UUID> entryIds = posted.stream()
                .map(BankReconciliationAdjustment::getJournalEntryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, JournalEntryStatus> statusById = ledgerEntries.statuses(entryIds);
        List<UUID> unposted = posted.stream()
                .filter(a -> a.getJournalEntryId() == null
                        || statusById.get(a.getJournalEntryId()) != JournalEntryStatus.POSTED)
                .map(BankReconciliationAdjustment::getAdjustmentId)
                .sorted()
                .toList();
        if (unposted.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(check(
                ReadinessCheckCode.UNPOSTED_ADJUSTMENTS,
                unposted.size() + " adjustment(s) have no POSTED journal entry",
                refs("adjustmentIds", ids(unposted.stream()))));
    }

    private Optional<CloseReadinessCheck> incompleteImports(UUID account, LocalDate end) {
        List<UUID> ids = importLookups
                .orderedStream()
                .flatMap(lookup -> lookup.incompleteImportIds(account, end).stream())
                .distinct()
                .sorted()
                .toList();
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(check(
                ReadinessCheckCode.INCOMPLETE_IMPORTS,
                ids.size() + " import(s) with rows dated on or before " + end + " are neither committed nor discarded",
                refs("importIds", ids(ids.stream()))));
    }

    private Optional<CloseReadinessCheck> outstandingAging(
            List<BankReconciliationOutstandingItem> open, LocalDate agingDay) {
        LocalDate agedBefore = agingDay.minusDays(settings.agingWarningDays());
        List<BankReconciliationOutstandingItem> aged =
                open.stream().filter(i -> i.getItemDate().isBefore(agedBefore)).toList();
        if (aged.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> ages = new LinkedHashMap<>();
        aged.stream()
                .limit(REFERENCE_LIMIT)
                .forEach(i -> ages.put(
                        i.getOutstandingItemId().toString(), ChronoUnit.DAYS.between(i.getItemDate(), agingDay)));
        Map<String, Object> refs = refs(
                "outstandingItemIds", ids(aged.stream().map(BankReconciliationOutstandingItem::getOutstandingItemId)));
        refs.put("ageDays", ages);
        refs.put("agingWarningDays", settings.agingWarningDays());
        return Optional.of(check(
                ReadinessCheckCode.OUTSTANDING_ITEMS_AGING,
                aged.size() + " OPEN outstanding item(s) older than " + settings.agingWarningDays() + " days at "
                        + agingDay,
                refs));
    }

    private Optional<CloseReadinessCheck> lateBankTransactions(UUID account, LocalDate start, LocalDate end) {
        List<BankTransaction> late =
                transactions.findByGlAccountIdAndArrivedAfterApprovalTrueAndTransactionDateBetweenAndStatusIn(
                        account, start, end, UNRESOLVED);
        if (late.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(check(
                ReadinessCheckCode.LATE_BANK_TRANSACTIONS,
                late.size() + " bank transaction(s) arrived after their window was approved and are unresolved",
                refs("bankTransactionIds", ids(late.stream().map(BankTransaction::getBankTransactionId)))));
    }

    private static Optional<CloseReadinessCheck> reconciledAfterClose(
            AccountingPeriod period, BankReconciliation covering) {
        if (period.getClosedAt() == null
                || covering.getFinalizedAt() == null
                || !covering.getFinalizedAt().isAfter(period.getClosedAt())) {
            return Optional.empty();
        }
        Map<String, Object> refs = refs("reconciliationIds", List.of(covering.getReconciliationId()));
        refs.put("finalizedAt", covering.getFinalizedAt());
        refs.put("closedAt", period.getClosedAt());
        return Optional.of(check(
                ReadinessCheckCode.RECONCILED_AFTER_CLOSE,
                "The covering reconciliation was approved after the period was closed",
                refs));
    }

    // ---- tenant-wide ------------------------------------------------------------------------------------

    private Optional<CloseReadinessCheck> draftEntries(LocalDate start, LocalDate end) {
        List<UUID> drafts = ledgerEntries.draftEntryIds(start, end);
        if (drafts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(check(
                ReadinessCheckCode.DRAFT_JOURNAL_ENTRIES,
                drafts.size() + " DRAFT journal entries are dated inside the period",
                refs("draftJournalEntryIds", ids(drafts.stream()))));
    }

    /**
     * §5.3 {@code CLEARING_BALANCE_AGING} (D2, §4.7): each distinct counter account a POSTED {@code OTHER}
     * adjustment's entry posted to — read from the entries, never from the mapping — whose as-of balance is
     * outside one minor unit of zero both at the period end and {@code clearing.aging-warning-days} before it.
     * Not evaluated when no {@code OTHER} adjustment was ever posted; never blocks.
     */
    private List<CloseReadinessCheck> clearingBalanceAging(LocalDate end) {
        List<BankReconciliationAdjustment> others = adjustments.findAllOfType(BankAdjustmentType.OTHER).stream()
                .filter(a -> a.getJournalEntryId() != null)
                .toList();
        if (others.isEmpty()) {
            return List.of();
        }
        Map<UUID, BankReconciliationAdjustment> byEntry = others.stream()
                .collect(Collectors.toMap(
                        BankReconciliationAdjustment::getJournalEntryId, Function.identity(), (a, b) -> a));
        Map<UUID, List<BankReconciliationAdjustment>> byClearingAccount = new LinkedHashMap<>();
        for (Map.Entry<UUID, Set<UUID>> entry :
                ledgerEntries.accountsOf(byEntry.keySet()).entrySet()) {
            BankReconciliationAdjustment adjustment = byEntry.get(entry.getKey());
            UUID bankAccount = adjustment.getReconciliation().getGlAccount().getGlAccountId();
            for (UUID account : entry.getValue()) {
                if (!account.equals(bankAccount)) {
                    byClearingAccount
                            .computeIfAbsent(account, k -> new ArrayList<>())
                            .add(adjustment);
                }
            }
        }
        if (byClearingAccount.isEmpty()) {
            return List.of();
        }
        LocalDate agingDate = end.minusDays(settings.clearingAgingWarningDays());
        BigDecimal tolerance = currency.tolerance();
        Map<UUID, BankCashAccount> accounts = bankCashAccounts.displayValues(byClearingAccount.keySet());
        List<CloseReadinessCheck> checks = new ArrayList<>();
        byClearingAccount.entrySet().stream()
                .sorted(Comparator.comparing(e -> accountCode(accounts.get(e.getKey()))))
                .forEach(entry -> {
                    UUID clearing = entry.getKey();
                    BigDecimal atEnd = ledger.balanceAsOf(clearing, end);
                    BigDecimal atAgingDate = ledger.balanceAsOf(clearing, agingDate);
                    if (atEnd.abs().compareTo(tolerance) <= 0
                            || atAgingDate.abs().compareTo(tolerance) <= 0) {
                        return;
                    }
                    List<UUID> aged = entry.getValue().stream()
                            .filter(a -> a.getStatus() == AdjustmentStatus.POSTED)
                            .filter(a -> a.getTransactionDate() != null
                                    && !a.getTransactionDate().isAfter(agingDate))
                            .map(BankReconciliationAdjustment::getAdjustmentId)
                            .distinct()
                            .sorted()
                            .toList();
                    String code = accountCode(accounts.get(clearing));
                    Map<String, Object> refs = refs("glAccountId", clearing, "accountCode", code);
                    refs.put("balanceAtPeriodEnd", atEnd);
                    refs.put("agingDate", agingDate);
                    refs.put("balanceAtAgingDate", atAgingDate);
                    refs.put("adjustmentIds", ids(aged.stream()));
                    checks.add(check(
                            ReadinessCheckCode.CLEARING_BALANCE_AGING,
                            "Clearing account " + code + " holds " + currency.display(atEnd) + " at " + end
                                    + " and " + currency.display(atAgingDate) + " at " + agingDate
                                    + "; it has not been cleared back to zero",
                            refs));
                });
        return checks;
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    private static boolean holds(String authority) {
        return SecurityContextHelper.isAuthenticated() && SecurityContextHelper.hasAuthority(authority);
    }

    private static String accountCode(@Nullable BankCashAccount account) {
        return account != null ? account.accountCode() : "";
    }

    private static LocalDate earlier(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private static int count(
            List<CloseReadinessCheck> tenantChecks, List<CloseReadinessAccount> accounts, ReadinessSeverity severity) {
        long tenant =
                tenantChecks.stream().filter(c -> c.severity() == severity).count();
        long perAccount = accounts.stream()
                .flatMap(a -> a.checks().stream())
                .filter(c -> c.severity() == severity)
                .count();
        return Math.toIntExact(tenant + perAccount);
    }

    private static CloseReadinessCheck check(ReadinessCheckCode code, String detail, Map<String, Object> references) {
        return new CloseReadinessCheck(code, code.severity(), detail, references);
    }

    private static List<UUID> ids(Stream<UUID> ids) {
        return ids.limit(REFERENCE_LIMIT).toList();
    }

    private static Map<String, Object> refs(String key, @Nullable Object value) {
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put(key, value);
        return refs;
    }

    private static Map<String, Object> refs(
            String key1, @Nullable Object value1, String key2, @Nullable Object value2) {
        Map<String, Object> refs = refs(key1, value1);
        refs.put(key2, value2);
        return refs;
    }
}
