package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.dto.BankReconciliationAdjustmentResponse;
import com.positivity.accounting.internal.bankrec.dto.BankReconciliationImportRequest;
import com.positivity.accounting.internal.bankrec.dto.BankReconciliationLineResponse;
import com.positivity.accounting.internal.bankrec.dto.BankReconciliationListResponse;
import com.positivity.accounting.internal.bankrec.dto.BankReconciliationResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationAuditResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationReportResponse;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationAdjustment;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationGlMatch;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.FeedChange;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.enums.SettlementState;
import com.positivity.accounting.internal.bankrec.enums.SourceKind;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationAdjustmentRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.BankCashAccounts.BankCashAccount;
import com.positivity.accounting.internal.bankrec.service.BankStatementCsvParser.ParsedLine;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.exception.AccountNotReconcilableException;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.exception.ReconciliationNotBalancedException;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bank reconciliation header lifecycle (SPEC-manual-bank-reconciliation §3.7, §4.1, §6.1; stories F2
 * #965, S1 #2300, S4 #2303): create from a COMMITTED statement, read with the live equation E3, list,
 * finalize over the live difference (balance-only until S5), report and derived audit. Matching,
 * outstanding items and adjustments live in their own services and share {@link ReconciliationSupport}.
 *
 * <p>Every read computes E3 live from the ledger ({@link ReconciliationCalculator}); every mutation stores
 * the terms on the row. The F2 {@code /import} path (retired by S3) still creates a statement and a
 * reconciliation in one call.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class BankReconciliationServiceImpl implements BankReconciliationService {

    private final Clock clock;
    private final BankReconciliationRepository reconciliationRepository;
    private final BankStatementRepository statementRepository;
    private final BankTransactionRepository transactionRepository;
    private final BankReconciliationGlMatchRepository glMatchRepository;
    private final BankReconciliationAdjustmentRepository adjustmentRepository;
    private final GLAccountRepository glAccountRepository;
    private final BankCashAccounts bankCashAccounts;
    private final ReconciliationCalculator calculator;
    private final ReconciliationSupport support;
    private final BankRecAuditRecorder auditRecorder;
    private final FunctionalCurrency functionalCurrency;

    @Override
    public BankReconciliationResponse create(@NonNull ReconciliationCreateRequest request) {
        BankCashAccount account = bankCashAccounts.requireForIntake(request.getGlAccountId());
        if (request.getStatementId() == null) {
            // Phase 1 (§4.1 d, §6.1): the statementless interim body belongs to a feed-linked account,
            // and no account has a feed link yet.
            throw new BankRecException(
                    BankRecErrorCode.BANK_ACCOUNT_FEED_NOT_LINKED,
                    "Account " + account.accountCode() + " has no bank-feed link; start the reconciliation from a"
                            + " COMMITTED statement (an interim to a date is a manual-entry statement)");
        }
        BankReconciliation replay =
                reconciliationRepository.findByRequestId(request.getRequestId()).orElse(null);
        if (replay != null) {
            if (!replay.getGlAccountId().equals(request.getGlAccountId())
                    || !request.getStatementId().equals(replay.getStatementId())) {
                throw new BankRecException(
                        BankRecErrorCode.IDEMPOTENCY_CONFLICT,
                        "requestId " + request.getRequestId() + " was already used with a different payload");
            }
            BankReconciliationResponse response = toResponse(replay, calculator.compute(replay));
            response.setReplayed(true);
            return response;
        }

        BankStatement statement = statementRepository
                .findById(request.getStatementId())
                .filter(s -> s.getGlAccountId().equals(request.getGlAccountId()))
                .filter(s -> s.getStatus() == BankStatementStatus.COMMITTED)
                .orElseThrow(() -> new BankRecException(
                        BankRecErrorCode.BANK_STATEMENT_NOT_FOUND,
                        "No COMMITTED statement " + request.getStatementId() + " on account " + account.accountCode()));
        requireWindowNotReconciled(statement);

        BankReconciliation recon = new BankReconciliation();
        recon.setGlAccountId(account.glAccountId());
        recon.setAccountCode(account.accountCode());
        recon.setAccountName(account.accountName());
        recon.setStatementId(statement.getStatementId());
        recon.setStatementStartDate(statement.getStartDate());
        recon.setStatementEndDate(statement.getEndDate());
        recon.setStatementOpeningBalance(statement.getOpeningBalance());
        recon.setStatementClosingBalance(statement.getClosingBalance());
        recon.setCurrency(statement.getCurrency());
        recon.setAccountingPeriodCode(YearMonth.from(statement.getEndDate()).toString());
        recon.setStatus(ReconciliationStatus.IN_PROGRESS);
        recon.setRequestId(request.getRequestId());
        ReconciliationSnapshot snapshot = calculator.compute(recon);
        ReconciliationCalculator.apply(recon, snapshot);
        BankReconciliation saved;
        try {
            // The partial unique (tenant_id, statement_id) WHERE status IN (IN_PROGRESS, SUBMITTED) is the
            // backstop for two creates racing past requireWindowNotReconciled.
            saved = reconciliationRepository.saveAndFlush(recon);
        } catch (DataIntegrityViolationException e) {
            throw new BankRecException(
                    BankRecErrorCode.RECONCILIATION_WINDOW_ALREADY_RECONCILED,
                    "Statement " + statement.getStatementId() + " was concurrently given a reconciliation");
        }
        auditRecorder.record(
                BankRecAuditRecorder.BANK_RECONCILIATION,
                saved.getReconciliationId(),
                BankRecAuditRecorder.RECONCILIATION_CREATE,
                support.currentUser(),
                null,
                null,
                "statementId=" + statement.getStatementId() + ";status=" + saved.getStatus());
        log.info(
                "Started reconciliation {} of statement {} on account {} ({}..{})",
                saved.getReconciliationId(),
                statement.getStatementId(),
                account.accountCode(),
                statement.getStartDate(),
                statement.getEndDate());
        return toResponse(saved, snapshot);
    }

    /**
     * §4.1, §6.4: a statement has at most one IN_PROGRESS or SUBMITTED reconciliation (partial unique), and a
     * FINALIZED one without a successor already reconciles it — the correction path is supersede (S5).
     */
    private void requireWindowNotReconciled(BankStatement statement) {
        List<BankReconciliation> existing = reconciliationRepository.findByStatementIdAndStatusIn(
                statement.getStatementId(),
                List.of(
                        ReconciliationStatus.IN_PROGRESS,
                        ReconciliationStatus.SUBMITTED,
                        ReconciliationStatus.FINALIZED));
        existing.stream()
                .filter(r ->
                        r.getStatus() != ReconciliationStatus.FINALIZED || r.getSupersededByReconciliationId() == null)
                .findFirst()
                .ifPresent(r -> {
                    throw BankRecException.field(
                            BankRecErrorCode.RECONCILIATION_WINDOW_ALREADY_RECONCILED,
                            "Statement " + statement.getStatementId() + " already has reconciliation "
                                    + r.getReconciliationId() + " (" + r.getStatus() + ")",
                            "reconciliationId",
                            r.getReconciliationId().toString());
                });
    }

    @Override
    public BankReconciliationResponse importStatement(@NonNull BankReconciliationImportRequest request) {
        GLAccount account = glAccountRepository
                .findById(request.getGlAccountId())
                .orElseThrow(() ->
                        new InvalidRequestParameterException("GL account not found: " + request.getGlAccountId()));
        if (!account.isReconcilable()) {
            throw new AccountNotReconcilableException(
                    "GL account " + account.getAccountCode() + " is not reconcilable");
        }

        List<ParsedLine> parsed = BankStatementCsvParser.parse(request.getCsv());

        // statementDate is retired (§3.7): the statement end date is the as-of date.
        LocalDate statementEndDate = request.getPeriodEndDate();
        String currency = request.getCurrency().toUpperCase();

        BigDecimal activityTotal = parsed.stream().map(ParsedLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BankStatement statement = new BankStatement();
        statement.setGlAccountId(request.getGlAccountId());
        statement.setSourceKind(SourceKind.FILE_IMPORT);
        statement.setStartDate(request.getPeriodStartDate());
        statement.setEndDate(statementEndDate);
        // F2's request carries no opening balance; E1 holds by construction (§6.4 conversion rule).
        statement.setClosingBalance(request.getStatementEndingBalance());
        statement.setActivityTotal(activityTotal);
        statement.setOpeningBalance(request.getStatementEndingBalance().subtract(activityTotal));
        statement.setCurrency(currency);
        statement.setStatus(BankStatementStatus.COMMITTED);
        BankStatement savedStatement = statementRepository.save(statement);

        Instant now = Instant.now(clock);
        List<BankTransaction> transactions = new ArrayList<>();
        int rowNumber = 1;
        for (ParsedLine p : parsed) {
            BankTransaction transaction = new BankTransaction();
            transaction.setGlAccountId(request.getGlAccountId());
            transaction.setStatementId(savedStatement.getStatementId());
            transaction.setSourceKind(SourceKind.FILE_IMPORT);
            transaction.setSourceRowNumber(rowNumber++);
            transaction.setSettlementState(SettlementState.POSTED);
            transaction.setTransactionDate(p.date());
            transaction.setSignedAmount(p.amount());
            transaction.setCurrency(currency);
            transaction.setDescription(p.description());
            transaction.setReference(p.reference());
            transaction.setStatus(BankTransactionStatus.UNMATCHED);
            transaction.setFeedChange(FeedChange.ADDED);
            transaction.setFirstObservedAt(now);
            transaction.setLastObservedAt(now);
            transactions.add(transaction);
        }
        transactionRepository.saveAll(transactions);

        BankReconciliation recon = new BankReconciliation();
        recon.setGlAccountId(request.getGlAccountId());
        recon.setAccountCode(account.getAccountCode());
        recon.setAccountName(account.getAccountName());
        recon.setStatementId(savedStatement.getStatementId());
        recon.setStatementStartDate(request.getPeriodStartDate());
        recon.setStatementEndDate(statementEndDate);
        recon.setStatementOpeningBalance(savedStatement.getOpeningBalance());
        recon.setCurrency(currency);
        recon.setStatementClosingBalance(request.getStatementEndingBalance());
        recon.setAccountingPeriodCode(YearMonth.from(statementEndDate).toString());
        recon.setStatus(ReconciliationStatus.IN_PROGRESS);
        ReconciliationSnapshot snapshot = calculator.compute(recon);
        ReconciliationCalculator.apply(recon, snapshot);

        BankReconciliation saved = reconciliationRepository.save(recon);
        log.info(
                "Imported bank reconciliation {} for account {} ({} lines, glEndingBalance={})",
                saved.getReconciliationId(),
                account.getAccountCode(),
                parsed.size(),
                saved.getGlEndingBalance());
        return toResponse(saved, snapshot);
    }

    @Override
    @Transactional(readOnly = true)
    public BankReconciliationResponse get(@NonNull UUID reconciliationId) {
        BankReconciliation recon = support.require(reconciliationId);
        return toResponse(recon, calculator.compute(recon));
    }

    @Override
    @Transactional(readOnly = true)
    public BankReconciliationListResponse list(@NonNull ReconciliationListFilter filter, @NonNull Pageable pageable) {
        Page<BankReconciliation> page = reconciliationRepository.findAll(filter.toSpecification(), pageable);
        // The list serves each row's terms as the last mutation stored them; GET /{id} and the review
        // read them live (§3.7) — recomputing every row of a page would read the ledger N times.
        List<BankReconciliationResponse> items =
                page.getContent().stream().map(BankReconciliationResponse::from).toList();
        BankReconciliationListResponse response = new BankReconciliationListResponse();
        response.setReconciliations(items);
        response.setTotalElements(page.getTotalElements());
        response.setPageNumber(page.getNumber());
        response.setPageSize(page.getSize());
        response.setTotalPages(page.getTotalPages());
        return response;
    }

    @Override
    public BankReconciliationResponse finalizeReconciliation(@NonNull UUID reconciliationId) {
        BankReconciliation recon = support.requireOpen(reconciliationId);
        // Balance-only gate over the live difference until S5 adds SUBMITTED, E4 and the approver.
        ReconciliationSnapshot snapshot = support.refresh(recon);
        BigDecimal difference = snapshot.terms().difference();
        if (!ReconciliationEquation.withinTolerance(difference, functionalCurrency.tolerance())) {
            throw new ReconciliationNotBalancedException(
                    "Reconciliation " + reconciliationId + " does not balance; difference " + difference, difference);
        }
        recon.setStatus(ReconciliationStatus.FINALIZED);
        recon.setFinalizedAt(Instant.now(clock));
        recon.setFinalizedBy(support.currentUser());
        reconciliationRepository.save(recon);
        log.info("Finalized reconciliation {} (difference={})", reconciliationId, difference);
        return toResponse(recon, snapshot);
    }

    @Override
    @Transactional(readOnly = true)
    public ReconciliationReportResponse report(@NonNull UUID reconciliationId) {
        BankReconciliation recon = support.require(reconciliationId);
        ReconciliationSnapshot snapshot = calculator.compute(recon);
        List<BankTransaction> lines = statementTransactions(recon);
        List<BankReconciliationAdjustment> adjustments =
                adjustmentRepository.findByReconciliation_ReconciliationId(reconciliationId);

        List<BankTransaction> matched =
                lines.stream().filter(BankReconciliationServiceImpl::isMatched).toList();
        List<BankTransaction> outstanding =
                lines.stream().filter(l -> !isMatched(l)).toList();

        return ReconciliationReportResponse.builder()
                .reconciliationId(reconciliationId)
                .accountCode(recon.getAccountCode())
                .accountName(recon.getAccountName())
                .currency(recon.getCurrency())
                .statementDate(recon.getStatementEndDate())
                .glEndingBalance(snapshot.terms().glEndingBalance())
                .statementEndingBalance(recon.getStatementClosingBalance())
                .totalMatched(sumBank(matched))
                .totalAdjustments(sumAdjustments(adjustments))
                .totalOutstanding(sumBank(outstanding))
                .matchedLineCount(matched.size())
                .outstandingLineCount(outstanding.size())
                .difference(snapshot.terms().difference())
                .adjustments(adjustments.stream()
                        .map(BankReconciliationAdjustmentResponse::from)
                        .toList())
                .outstandingLines(outstanding.stream()
                        .map(l -> BankReconciliationLineResponse.from(l, null))
                        .toList())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public ReconciliationAuditResponse audit(@NonNull UUID reconciliationId) {
        BankReconciliation recon = support.require(reconciliationId);
        List<BankReconciliationAdjustment> adjustments =
                adjustmentRepository.findByReconciliation_ReconciliationId(reconciliationId);
        // F2's derived trail lists surviving match groups only; unmatched history stays out of it
        // until the stored audit trail of story S5.
        List<BankReconciliationGlMatch> matches =
                glMatchRepository.findByReconciliationIdAndActiveTrue(reconciliationId);

        List<ReconciliationAuditResponse.Entry> entries = new ArrayList<>();
        entries.add(ReconciliationAuditResponse.Entry.builder()
                .action("IMPORT")
                .at(recon.getCreatedAt())
                .by(recon.getCreatedBy())
                .detail("Imported statement for account " + recon.getAccountCode())
                .build());
        // One MATCH entry per distinct match group (earliest gl-match row time).
        matches.stream()
                .collect(java.util.stream.Collectors.groupingBy(BankReconciliationGlMatch::getMatchId))
                .forEach((matchId, group) -> {
                    Instant at = group.stream()
                            .map(BankReconciliationGlMatch::getCreatedAt)
                            .filter(java.util.Objects::nonNull)
                            .min(Comparator.naturalOrder())
                            .orElse(null);
                    entries.add(ReconciliationAuditResponse.Entry.builder()
                            .action("MATCH")
                            .at(at)
                            .by(null)
                            .detail("Match " + matchId + " (" + group.size() + " GL line(s))")
                            .build());
                });
        for (BankReconciliationAdjustment a : adjustments) {
            entries.add(ReconciliationAuditResponse.Entry.builder()
                    .action("ADJUSTMENT")
                    .at(a.getCreatedAt())
                    .by(a.getCreatedBy())
                    .detail(a.getAdjustmentType() + " " + a.getAmount() + " (JE " + a.getJournalEntryId() + ")")
                    .build());
        }
        if (recon.getStatus() == ReconciliationStatus.FINALIZED) {
            entries.add(ReconciliationAuditResponse.Entry.builder()
                    .action("FINALIZE")
                    .at(recon.getFinalizedAt())
                    .by(recon.getFinalizedBy())
                    .detail("Finalized with difference " + recon.getDifference())
                    .build());
        }
        entries.sort(Comparator.comparing(
                ReconciliationAuditResponse.Entry::getAt, Comparator.nullsLast(Comparator.naturalOrder())));

        return ReconciliationAuditResponse.builder()
                .reconciliationId(reconciliationId)
                .entries(entries)
                .build();
    }

    // ---- helpers -----------------------------------------------------------

    /** The reconciliation's statement lines: its statement's bank transactions in file order. */
    private List<BankTransaction> statementTransactions(@NonNull BankReconciliation recon) {
        if (recon.getStatementId() == null) {
            return List.of();
        }
        return transactionRepository.findByStatementIdOrderBySourceRowNumberAsc(recon.getStatementId());
    }

    private static boolean isMatched(BankTransaction transaction) {
        return transaction.getStatus() == BankTransactionStatus.MATCHED;
    }

    private static BigDecimal sumBank(List<BankTransaction> transactions) {
        return transactions.stream().map(BankTransaction::getSignedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sumAdjustments(List<BankReconciliationAdjustment> adjustments) {
        return adjustments.stream()
                .map(BankReconciliationAdjustment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The header with the live terms of {@code snapshot} applied (not stored on a read). */
    private static BankReconciliationResponse toResponse(
            @NonNull BankReconciliation recon, @NonNull ReconciliationSnapshot snapshot) {
        ReconciliationCalculator.apply(recon, snapshot);
        return BankReconciliationResponse.from(recon);
    }
}
