package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.dto.BankReconciliationResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationJustificationRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationReasonRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationTransitionRequest;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.MatchOrigin;
import com.positivity.accounting.internal.bankrec.enums.MatchState;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.intake.Justification;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.MatchWriter.Header;
import com.positivity.accounting.internal.bankrec.service.ReconciliationLifecycle.ReleasedMatch;
import com.positivity.accounting.internal.exception.ReconciliationAlreadyFinalizedException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The approval workflow (SPEC-manual-bank-reconciliation §3.8, §4.9, D2, D3; story S5, #2304).
 *
 * <p>Submit and approve lock the reconciliation row ({@code FOR UPDATE}), recompute every live term, the
 * baseline and both unexplained counts inside the transaction and evaluate the gate E4 ({@link ApprovalGate})
 * on them, so nothing is submitted or approved on a stale figure and a concurrent posting into the window
 * either lands before the lock (and is seen) or after the approval (and invalidates it through the
 * ledger-change hook) — I3. Approval snapshots {@code approvedGlEndingBalance} and {@code baselineDate} and
 * supersedes the reconciliation it corrects.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ReconciliationApprovalServiceImpl implements ReconciliationApprovalService {

    static final String RECONCILIATION_CANCELLED = "RECONCILIATION_CANCELLED";

    /** Statuses an approved window can be superseded from (§4.9 path 1). */
    private static final Set<ReconciliationStatus> SUPERSEDABLE =
            EnumSet.of(ReconciliationStatus.FINALIZED, ReconciliationStatus.INVALIDATED);

    private final ReconciliationSupport support;
    private final ApprovalGate gate;
    private final BankRecPolicy policy;
    private final ReconciliationLifecycle lifecycle;
    private final MatchWriter writer;
    private final BankReconciliationRepository reconciliations;
    private final BankReconciliationMatchRepository matches;
    private final BankReconciliationOutstandingItemRepository items;
    private final BankStatementRepository statements;
    private final BankTransactionRepository transactions;
    private final BankRecAuditRecorder audit;
    private final BankReconciliationFacts facts;

    @Override
    public @NonNull BankReconciliationResponse submit(
            @NonNull UUID reconciliationId, @Nullable ReconciliationTransitionRequest request) {
        BankReconciliation recon = support.lock(reconciliationId);
        ReconciliationSupport.requireVersion(recon, request == null ? null : request.getVersion());
        ReconciliationSupport.requireStatus(recon, ReconciliationStatus.IN_PROGRESS);
        ReconciliationSnapshot snapshot = support.refresh(recon);
        gate.require(snapshot);

        String actor = support.currentUser();
        recon.setStatus(ReconciliationStatus.SUBMITTED);
        recon.setSubmittedAt(support.now());
        recon.setSubmittedBy(actor);
        BankReconciliation saved = reconciliations.saveAndFlush(recon);
        audit.record(
                BankRecAuditRecorder.BANK_RECONCILIATION,
                reconciliationId,
                BankRecAuditRecorder.RECONCILIATION_SUBMIT,
                actor,
                null,
                "status=" + ReconciliationStatus.IN_PROGRESS,
                "status=" + ReconciliationStatus.SUBMITTED + ";difference="
                        + saved.getDifference().toPlainString());
        facts.submitted(saved, actor);
        log.info("Submitted reconciliation {} for approval", reconciliationId);
        return BankReconciliationResponse.from(saved);
    }

    @Override
    public @NonNull BankReconciliationResponse approve(
            @NonNull UUID reconciliationId, @Nullable ReconciliationTransitionRequest request) {
        BankReconciliation recon = support.lock(reconciliationId);
        ReconciliationSupport.requireVersion(recon, request == null ? null : request.getVersion());
        if (recon.getStatus() == ReconciliationStatus.FINALIZED) {
            throw new ReconciliationAlreadyFinalizedException(
                    "Reconciliation " + reconciliationId + " is already FINALIZED");
        }
        if (recon.getStatus() != ReconciliationStatus.SUBMITTED) {
            throw new BankRecException(
                    BankRecErrorCode.RECONCILIATION_NOT_SUBMITTED,
                    "Reconciliation " + reconciliationId + " is " + recon.getStatus() + "; only a SUBMITTED"
                            + " reconciliation is approved");
        }
        String actor = support.currentUser();
        boolean selfApproval = actor.equals(recon.getSubmittedBy());
        if (selfApproval && !policy.allowSelfApproval()) {
            // D3: refused and audited; the audit row survives the 403's rollback.
            audit.recordIndependently(
                    BankRecAuditRecorder.BANK_RECONCILIATION,
                    reconciliationId,
                    BankRecAuditRecorder.RECONCILIATION_APPROVE,
                    actor,
                    null,
                    "status=" + ReconciliationStatus.SUBMITTED + ";submittedBy=" + recon.getSubmittedBy(),
                    "outcome=REFUSED;reason=" + BankRecErrorCode.RECONCILIATION_SELF_APPROVAL + ";approvedBy=" + actor);
            throw new BankRecException(
                    BankRecErrorCode.RECONCILIATION_SELF_APPROVAL,
                    "Reconciliation " + reconciliationId + " was submitted by the caller; another user holding"
                            + " accounting:reconciliation:approve approves it");
        }

        // I3: the live balance, every E3 term, the baseline and both counts, read under the row lock.
        ReconciliationSnapshot snapshot = support.refresh(recon);
        gate.require(snapshot);

        recon.setStatus(ReconciliationStatus.FINALIZED);
        recon.setFinalizedAt(support.now());
        recon.setFinalizedBy(actor);
        recon.setApprovedGlEndingBalance(snapshot.terms().glEndingBalance());
        recon.setBaselineDate(snapshot.baselineDate());
        BankReconciliation predecessor = predecessorOf(recon);
        if (predecessor != null) {
            recon.setSupersedesReconciliationId(predecessor.getReconciliationId());
        }
        BankReconciliation saved = reconciliations.saveAndFlush(recon);
        audit.record(
                BankRecAuditRecorder.BANK_RECONCILIATION,
                reconciliationId,
                BankRecAuditRecorder.RECONCILIATION_APPROVE,
                actor,
                null,
                "status=" + ReconciliationStatus.SUBMITTED + ";submittedBy=" + saved.getSubmittedBy(),
                "status=" + ReconciliationStatus.FINALIZED + ";approvedBy=" + actor + ";selfApproval=" + selfApproval
                        + ";approvedGlEndingBalance="
                        + saved.getApprovedGlEndingBalance().toPlainString()
                        + ";baselineDate=" + saved.getBaselineDate());
        facts.approved(saved, actor);
        if (predecessor != null) {
            lifecycle.markSuperseded(predecessor, saved, actor);
        }
        log.info(
                "Approved reconciliation {} (approvedGlEndingBalance={}, selfApproval={})",
                reconciliationId,
                saved.getApprovedGlEndingBalance(),
                selfApproval);
        return BankReconciliationResponse.from(saved);
    }

    /**
     * The approved or invalidated reconciliation this approval replaces (§4.9 paths 1 and 3): the one it was
     * created to supersede, else the latest unsuperseded one of the same statement or of a statement the
     * reconciled statement superseded.
     */
    private @Nullable BankReconciliation predecessorOf(BankReconciliation recon) {
        if (recon.getSupersedesReconciliationId() != null) {
            return reconciliations
                    .findById(recon.getSupersedesReconciliationId())
                    .filter(p -> SUPERSEDABLE.contains(p.getStatus()))
                    .orElse(null);
        }
        if (recon.getStatementId() == null) {
            return null;
        }
        List<UUID> statementIds = new ArrayList<>();
        statementIds.add(recon.getStatementId());
        statements.findBySupersededByStatementId(recon.getStatementId()).stream()
                .map(BankStatement::getStatementId)
                .forEach(statementIds::add);
        return reconciliations.findByStatementIdInAndStatusIn(statementIds, SUPERSEDABLE).stream()
                .filter(p -> !p.getReconciliationId().equals(recon.getReconciliationId()))
                .filter(p -> p.getSupersededByReconciliationId() == null)
                .max(Comparator.comparing(BankReconciliation::getReconciliationId))
                .orElse(null);
    }

    @Override
    public @NonNull BankReconciliationResponse returnToPreparer(
            @NonNull UUID reconciliationId, @NonNull ReconciliationReasonRequest request) {
        String reason = Justification.required(request.getReason(), "reason");
        BankReconciliation recon = support.lock(reconciliationId);
        ReconciliationSupport.requireVersion(recon, request.getVersion());
        if (recon.getStatus() != ReconciliationStatus.SUBMITTED) {
            throw new BankRecException(
                    BankRecErrorCode.RECONCILIATION_NOT_SUBMITTED,
                    "Reconciliation " + reconciliationId + " is " + recon.getStatus() + "; only a SUBMITTED"
                            + " reconciliation is returned");
        }
        String actor = support.currentUser();
        String submittedBy = recon.getSubmittedBy();
        recon.setStatus(ReconciliationStatus.IN_PROGRESS);
        recon.setSubmittedAt(null);
        recon.setSubmittedBy(null);
        BankReconciliation saved = reconciliations.saveAndFlush(recon);
        audit.record(
                BankRecAuditRecorder.BANK_RECONCILIATION,
                reconciliationId,
                BankRecAuditRecorder.RECONCILIATION_RETURN,
                actor,
                reason,
                "status=" + ReconciliationStatus.SUBMITTED + ";submittedBy=" + submittedBy,
                "status=" + ReconciliationStatus.IN_PROGRESS);
        log.info("Returned reconciliation {} to its preparer", reconciliationId);
        return BankReconciliationResponse.from(saved);
    }

    @Override
    public @NonNull BankReconciliationResponse cancel(
            @NonNull UUID reconciliationId, @NonNull ReconciliationJustificationRequest request) {
        String justification = Justification.required(request.getJustification(), "justification");
        BankReconciliation recon = support.lock(reconciliationId);
        ReconciliationSupport.requireVersion(recon, request.getVersion());
        ReconciliationStatus previous = recon.getStatus();
        ReconciliationSupport.requireStatus(recon, ReconciliationStatus.IN_PROGRESS, ReconciliationStatus.SUBMITTED);
        String actor = support.currentUser();

        // Its live matches are unmatched (members inactive, bank rows back to UNMATCHED, items they cleared
        // re-opened); the OPEN items it registered are released; posted adjustments stay (§4.9).
        int unmatched = 0;
        for (BankReconciliationMatch match : matches.findByReconciliationIdOrderByCreatedAtAsc(reconciliationId)) {
            if (match.getState() == MatchState.PROPOSED || match.getState() == MatchState.ACCEPTED) {
                writer.end(match, MatchState.UNMATCHED, RECONCILIATION_CANCELLED, actor);
                unmatched++;
            }
        }
        Instant now = support.now();
        List<BankReconciliationOutstandingItem> released =
                items.findByRegisteredInReconciliationIdAndStatus(reconciliationId, OutstandingItemStatus.OPEN);
        for (BankReconciliationOutstandingItem item : released) {
            item.setStatus(OutstandingItemStatus.RELEASED);
            item.setReleasedAt(now);
            item.setReleasedBy(actor);
            item.setReleaseReason(RECONCILIATION_CANCELLED);
        }
        items.saveAll(released);

        recon.setStatus(ReconciliationStatus.CANCELLED);
        recon.setCancelledAt(now);
        recon.setCancelledBy(actor);
        recon.setCancelReason(justification);
        BankReconciliation saved = reconciliations.saveAndFlush(recon);
        audit.record(
                BankRecAuditRecorder.BANK_RECONCILIATION,
                reconciliationId,
                BankRecAuditRecorder.RECONCILIATION_CANCEL,
                actor,
                justification,
                "status=" + previous,
                "status=" + ReconciliationStatus.CANCELLED + ";matchesUnmatched=" + unmatched + ";itemsReleased="
                        + released.size());
        facts.cancelled(saved, justification, actor);
        log.info(
                "Cancelled reconciliation {} ({} match(es) unmatched, {} item(s) released)",
                reconciliationId,
                unmatched,
                released.size());
        return BankReconciliationResponse.from(saved);
    }

    @Override
    public @NonNull BankReconciliationResponse supersede(
            @NonNull UUID reconciliationId, @NonNull ReconciliationJustificationRequest request) {
        String justification = Justification.required(request.getJustification(), "justification");
        String requestHash = supersedeHash(reconciliationId, justification, request.getVersion());
        if (request.getRequestId() != null) {
            BankReconciliation replay =
                    reconciliations.findByRequestId(request.getRequestId()).orElse(null);
            if (replay != null) {
                boolean same = replay.getRequestHash() != null
                        ? replay.getRequestHash().equals(requestHash)
                        : reconciliationId.equals(replay.getSupersedesReconciliationId());
                if (!same) {
                    throw new BankRecException(
                            BankRecErrorCode.IDEMPOTENCY_CONFLICT,
                            "requestId " + request.getRequestId() + " was already used with a different payload");
                }
                BankReconciliationResponse response = BankReconciliationResponse.from(replay);
                response.setReplayed(true);
                return response;
            }
        }
        // The statement is locked before the reconciliation, the order statement supersession takes them in
        // (statement, then its reconciliations), so the two serialize without a deadlock (§4.9 path 3).
        Optional<BankStatement> lockedStatement =
                reconciliations.findStatementIdById(reconciliationId).flatMap(statements::lockById);
        BankReconciliation predecessor = support.lock(reconciliationId);
        ReconciliationSupport.requireVersion(predecessor, request.getVersion());
        requireSupersedable(predecessor);
        BankStatement statement = lockedStatement
                .filter(s -> s.getStatus() == BankStatementStatus.COMMITTED)
                .orElseThrow(() -> new BankRecException(
                        BankRecErrorCode.RECONCILIATION_NOT_EDITABLE,
                        "The statement of reconciliation " + reconciliationId + " was superseded by a corrected"
                                + " statement; reconcile the corrected statement instead"));
        String actor = support.currentUser();

        BankReconciliation successor = new BankReconciliation();
        successor.setGlAccountId(predecessor.getGlAccountId());
        successor.setAccountCode(predecessor.getAccountCode());
        successor.setAccountName(predecessor.getAccountName());
        successor.setStatementId(statement.getStatementId());
        successor.setStatementStartDate(statement.getStartDate());
        successor.setStatementEndDate(statement.getEndDate());
        successor.setStatementOpeningBalance(statement.getOpeningBalance());
        successor.setStatementClosingBalance(statement.getClosingBalance());
        successor.setCurrency(statement.getCurrency());
        successor.setAccountingPeriodCode(predecessor.getAccountingPeriodCode());
        successor.setStatus(ReconciliationStatus.IN_PROGRESS);
        successor.setSupersedesReconciliationId(predecessor.getReconciliationId());
        successor.setRequestId(request.getRequestId());
        successor.setRequestHash(request.getRequestId() == null ? null : requestHash);
        // Terms are stored after the members move; the create needs non-null balances.
        successor.setGlEndingBalance(predecessor.getGlEndingBalance());
        BankReconciliation saved;
        try {
            saved = reconciliations.saveAndFlush(successor);
        } catch (DataIntegrityViolationException e) {
            // The partial unique (tenant_id, statement_id) WHERE status IN (IN_PROGRESS, SUBMITTED).
            throw new BankRecException(
                    BankRecErrorCode.RECONCILIATION_WINDOW_ALREADY_RECONCILED,
                    "Statement " + statement.getStatementId() + " was concurrently given a reconciliation");
        }

        // Path 1: the predecessor's members are released (headers keep their sealed state) and re-proposed
        // in the successor, so the preparer re-confirms each judgement; OPEN items carry over untouched (O2).
        List<ReleasedMatch> released = lifecycle.releaseMembers(predecessor);
        int reproposed = 0;
        for (ReleasedMatch old : released) {
            if (old.match().getState() != MatchState.ACCEPTED) {
                continue;
            }
            List<BankTransaction> rows = transactions.findAllById(old.bankTransactionIds());
            writer.create(
                    saved,
                    new Header(
                            old.match().getMatchKind(),
                            MatchState.PROPOSED,
                            old.match().getOrigin() == null
                                    ? MatchOrigin.USER
                                    : old.match().getOrigin(),
                            old.match().getConfidenceScore(),
                            old.match().getReasons(),
                            old.match().getJustification(),
                            null,
                            null),
                    rows,
                    old.ledgerMembers(),
                    actor);
            reproposed++;
        }
        support.refresh(saved);

        audit.record(
                BankRecAuditRecorder.BANK_RECONCILIATION,
                predecessor.getReconciliationId(),
                BankRecAuditRecorder.RECONCILIATION_SUPERSEDE,
                actor,
                justification,
                "status=" + predecessor.getStatus(),
                "successorReconciliationId=" + saved.getReconciliationId() + ";membersReleased=" + released.size());
        audit.record(
                BankRecAuditRecorder.BANK_RECONCILIATION,
                saved.getReconciliationId(),
                BankRecAuditRecorder.RECONCILIATION_CREATE,
                actor,
                justification,
                "supersedesReconciliationId=" + predecessor.getReconciliationId(),
                "statementId=" + statement.getStatementId() + ";status=" + saved.getStatus() + ";matchesProposed="
                        + reproposed);
        log.info(
                "Reconciliation {} supersedes {} ({} match(es) re-proposed)",
                saved.getReconciliationId(),
                predecessor.getReconciliationId(),
                reproposed);
        return BankReconciliationResponse.from(saved);
    }

    /**
     * The canonical hash of the whole supersede command (§6.3): the predecessor, the justification as stored
     * (trimmed) and the version it was issued against, so a reused {@code requestId} with any changed field is
     * {@code IDEMPOTENCY_CONFLICT}, not a replay.
     */
    static @NonNull String supersedeHash(
            @NonNull UUID reconciliationId, @NonNull String justification, @Nullable Long version) {
        return new CanonicalRequestHash()
                .field(reconciliationId)
                .field(justification)
                .field(version)
                .digest();
    }

    /**
     * Supersede accepts {@code FINALIZED} or {@code INVALIDATED} only: an {@code IN_PROGRESS} or {@code SUBMITTED}
     * one is the window's active reconciliation already (409 {@code RECONCILIATION_WINDOW_ALREADY_RECONCILED});
     * {@code SUPERSEDED} or {@code CANCELLED} ones are closed (409 {@code RECONCILIATION_NOT_EDITABLE}).
     */
    private static void requireSupersedable(BankReconciliation recon) {
        ReconciliationStatus status = recon.getStatus();
        if (SUPERSEDABLE.contains(status)) {
            if (recon.getSupersededByReconciliationId() != null) {
                throw new BankRecException(
                        BankRecErrorCode.RECONCILIATION_NOT_EDITABLE,
                        "Reconciliation " + recon.getReconciliationId() + " is already superseded");
            }
            return;
        }
        if (status == ReconciliationStatus.IN_PROGRESS || status == ReconciliationStatus.SUBMITTED) {
            throw BankRecException.field(
                    BankRecErrorCode.RECONCILIATION_WINDOW_ALREADY_RECONCILED,
                    "Reconciliation " + recon.getReconciliationId() + " is " + status + "; only a FINALIZED or"
                            + " INVALIDATED reconciliation is superseded",
                    "reconciliationId",
                    recon.getReconciliationId().toString());
        }
        throw new BankRecException(
                BankRecErrorCode.RECONCILIATION_NOT_EDITABLE,
                "Reconciliation " + recon.getReconciliationId() + " is " + status + " and cannot be superseded");
    }
}
