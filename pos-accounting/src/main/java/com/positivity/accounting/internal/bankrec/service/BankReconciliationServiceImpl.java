package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.dto.BankReconciliationListResponse;
import com.positivity.accounting.internal.bankrec.dto.BankReconciliationResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationAuditResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationReportResponse;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationAdjustment;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationGlMatch;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationAdjustmentRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.service.BankCashAccounts.BankCashAccount;
import java.time.Clock;
import java.time.Instant;
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
 * the terms on the row. Statements enter through the intake port (a file import or a manual statement);
 * the F2 CSV import endpoint is retired (D14).
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class BankReconciliationServiceImpl implements BankReconciliationService {

    private final Clock clock;
    private final BankReconciliationRepository reconciliationRepository;
    private final BankStatementRepository statementRepository;
    private final BankReconciliationGlMatchRepository glMatchRepository;
    private final BankReconciliationAdjustmentRepository adjustmentRepository;
    private final BankCashAccounts bankCashAccounts;
    private final ReconciliationCalculator calculator;
    private final ReconciliationSupport support;
    private final BankRecAuditRecorder auditRecorder;
    private final FunctionalCurrency functionalCurrency;
    private final ReconciliationReviewService reviewService;

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
     * FINALIZED or INVALIDATED one without a successor already reconciles it — the correction path is
     * {@code POST /{id}/supersede}, which carries its matches over (§4.9 path 1; S5, #2304).
     */
    private void requireWindowNotReconciled(BankStatement statement) {
        List<BankReconciliation> existing = reconciliationRepository.findByStatementIdAndStatusIn(
                statement.getStatementId(),
                List.of(
                        ReconciliationStatus.IN_PROGRESS,
                        ReconciliationStatus.SUBMITTED,
                        ReconciliationStatus.FINALIZED,
                        ReconciliationStatus.INVALIDATED));
        existing.stream()
                .filter(r -> r.getSupersededByReconciliationId() == null)
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
    @Transactional(readOnly = true)
    public ReconciliationReportResponse report(@NonNull UUID reconciliationId) {
        return reviewService.report(reconciliationId);
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

    /**
     * The header with the live terms of {@code snapshot} applied (not stored on a read). An approved
     * reconciliation keeps the {@code baselineDate} snapshotted at its approval (§3.7; S5, #2304).
     */
    private static BankReconciliationResponse toResponse(
            @NonNull BankReconciliation recon, @NonNull ReconciliationSnapshot snapshot) {
        java.time.LocalDate approvedBaseline = recon.getBaselineDate();
        boolean approved = recon.getFinalizedAt() != null;
        ReconciliationCalculator.apply(recon, snapshot);
        BankReconciliationResponse response = BankReconciliationResponse.from(recon);
        if (approved) {
            response.setBaselineDate(approvedBaseline);
        }
        return response;
    }
}
