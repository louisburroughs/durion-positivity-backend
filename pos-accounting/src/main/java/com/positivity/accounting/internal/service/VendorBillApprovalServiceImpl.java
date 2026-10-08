package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.VendorBillCommands;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillGlPosting;
import com.positivity.accounting.internal.entity.VendorBillMatchCandidate;
import com.positivity.accounting.internal.entity.VendorBillMatchEvidence;
import com.positivity.accounting.internal.enums.MatchConfidence;
import com.positivity.accounting.internal.enums.VendorBillAction;
import com.positivity.accounting.internal.enums.VendorBillApproverKind;
import com.positivity.accounting.internal.enums.VendorBillDebitClass;
import com.positivity.accounting.internal.enums.VendorBillDifferenceClass;
import com.positivity.accounting.internal.enums.VendorBillStage;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.APPaymentAllocationRepository;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.VendorBillMatchCandidateRepository;
import com.positivity.accounting.internal.repository.VendorBillMatchEvidenceRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.security.AccountingPermissions;
import java.io.Serial;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The vendor-bill approval lifecycle (CAP:550 S12, #2509; SPEC-accounting-workspace §4.3; AW8, AW37-AW43). See
 * {@link VendorBillApprovalService} for the contract.
 *
 * <p><b>Transactions.</b> Every command runs in {@link #commandTransaction}, joining a caller's when there is one. A
 * refused posting at approval rolls the whole approval back (AW42), and its refusal audit row is then written in a
 * transaction of its own ({@link #refusalTransaction}), so it survives the rollback: one row per refused approval.
 *
 * <p><b>Guard order</b> (CAP:550 S13, #2510, ruling 1) on approve, {@code ACCEPT} and the void of an approved bill. The
 * first guard that fails answers, before anything is written:
 *
 * <ol>
 *   <li>the endpoint gate and {@link VendorBillDecisions#require} (403 {@code FORBIDDEN});
 *   <li>the bill's state ({@link #readyState}): its status and an open ambiguous match (409 {@code
 *       AP_BILL_NOT_APPROVABLE}), for approve and {@code ACCEPT} a goods-receipt bill's matched invoice (409 {@code
 *       AP_BILL_AWAITING_INVOICE}, AW45), for the void an allocation (409 {@code AP_BILL_NOT_VOIDABLE});
 *   <li>the tier ({@link #requireTier}): an {@code OVER_LIMIT} bill needs {@code accounting:ap:approve_over_limit}
 *       (403 {@code AP_APPROVAL_LIMIT_EXCEEDED});
 *   <li>creator is not approver ({@link #creatorRule}), approve and {@code ACCEPT} only (403 {@code
 *       AP_BILL_SELF_APPROVAL}, or 400 {@code JUSTIFICATION_REQUIRED} for an exception use without one);
 *   <li>the bill's content ({@link #readyContent}): 422 {@code AP_BILL_ZERO_TOTAL}, {@code
 *       AP_BILL_TOTALS_UNRECONCILED} (AW47); S43 adds {@code AP_BILL_TAX_ON_RESALE_GOODS} at the end of this step;
 *   <li>the posting ({@link VendorBillPostingService#post}): {@code AP_BILL_UNCLASSIFIED}, {@code PERIOD_CLOSED},
 *       {@code PERIOD_HARD_LOCKED}, {@code GL_MAPPING_NOT_CONFIGURED}.
 * </ol>
 *
 * The identity guards come before the content guards, so someone who may not decide is never asked for a {@code
 * difference}. Each 403 of steps 3 and 4 is audited in its own transaction ({@code <operation>_REFUSED}), as a
 * refused posting is. Submit runs steps 2 and 5 only: the limit applies at decision time, never at submission.
 *
 * <p><b>Audit.</b> One {@code accounting_audit_log} row per decision (entity type {@value #AUDIT_ENTITY_TYPE}): the
 * actor, the tier from the policy in force, the clerk ({@code limit}) and automatic ({@code autoLimit}) limits, the
 * exception switch used ({@code exception}: {@code CREATOR_APPROVAL} or {@code NONE}), the bill's total and currency,
 * the latest match score and evidence id, the justification and, for an approval, the entry and its date. An
 * exception use writes a {@value #AUDIT_SOD_EXCEPTION} row too.
 */
@Slf4j
@Service
public class VendorBillApprovalServiceImpl implements VendorBillApprovalService {

    static final String AUDIT_ENTITY_TYPE = "VENDOR_BILL";
    static final String AUDIT_SUBMIT = "VENDOR_BILL_SUBMIT";
    static final String AUDIT_APPROVE = "VENDOR_BILL_APPROVE";
    static final String AUDIT_REJECT = "VENDOR_BILL_REJECT";
    static final String AUDIT_RESOLVE = "VENDOR_BILL_MATCH_EXCEPTION_RESOLVE";
    static final String AUDIT_SELECT = "VENDOR_BILL_MATCH_CANDIDATE_SELECT";
    static final String AUDIT_VOID = "VENDOR_BILL_VOID";
    static final String AUDIT_RELEASE = "VENDOR_BILL_MATCH_CANDIDATE_RELEASE";
    static final String AUDIT_DUE_DATE_SET = "VENDOR_BILL_DUE_DATE_SET";

    /** One row per use of a separation-of-duties exception switch (§4.3, AW6; CAP:550 S13, #2510). */
    static final String AUDIT_SOD_EXCEPTION = "VENDOR_BILL_SOD_EXCEPTION";

    /** {@code exception=} of a decision row: the creator approved under {@code AP_ALLOW_CREATOR_APPROVAL}. */
    static final String EXCEPTION_CREATOR_APPROVAL = "CREATOR_APPROVAL";

    /** {@code exception=} of a decision row that used no exception switch. */
    static final String EXCEPTION_NONE = "NONE";

    /**
     * Suffix of the audit operation that records a decision refused by its posting (AW42), or by the tier or the
     * creator rule (S13).
     */
    static final String AUDIT_REFUSED_SUFFIX = "_REFUSED";

    private static final Set<VendorBillStatus> SUBMITTABLE =
            EnumSet.of(VendorBillStatus.PENDING_RECEIPT_MATCH, VendorBillStatus.MATCH_EXCEPTION);

    private final Clock clock;
    private final VendorBillRepository bills;
    private final VendorBillMatchCandidateRepository candidates;
    private final VendorBillMatchEvidenceRepository evidence;
    private final APPaymentAllocationRepository allocations;
    private final AccountingAuditLogRepository auditLogs;
    private final VendorBillPostingService postingService;
    private final VendorBillInvoiceMatcher matcher;
    private final VendorBillDuplicateGuard duplicateGuard;
    private final VendorBillReader reader;
    private final VendorBillLocks locks;
    private final LedgerCurrency ledgerCurrency;
    private final ApApprovalPolicy policy;
    private final TransactionTemplate commandTransaction;
    private final TransactionTemplate refusalTransaction;

    public VendorBillApprovalServiceImpl(
            Clock clock,
            VendorBillRepository bills,
            VendorBillMatchCandidateRepository candidates,
            VendorBillMatchEvidenceRepository evidence,
            APPaymentAllocationRepository allocations,
            AccountingAuditLogRepository auditLogs,
            VendorBillPostingService postingService,
            VendorBillInvoiceMatcher matcher,
            VendorBillDuplicateGuard duplicateGuard,
            VendorBillReader reader,
            VendorBillLocks locks,
            LedgerCurrency ledgerCurrency,
            ApApprovalPolicy policy,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.bills = bills;
        this.candidates = candidates;
        this.evidence = evidence;
        this.allocations = allocations;
        this.auditLogs = auditLogs;
        this.postingService = postingService;
        this.matcher = matcher;
        this.duplicateGuard = duplicateGuard;
        this.reader = reader;
        this.locks = locks;
        this.ledgerCurrency = ledgerCurrency;
        this.policy = policy;
        this.commandTransaction = new TransactionTemplate(transactionManager);
        this.refusalTransaction = new TransactionTemplate(transactionManager);
        this.refusalTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---- commands -------------------------------------------------------------------------------------------

    @Override
    public @NonNull VendorBillResponse submitForApproval(
            @NonNull UUID billId, VendorBillCommands.@NonNull Submit command) {
        VendorBillDecisions.require(VendorBillAction.SUBMIT_FOR_APPROVAL);
        String actor = VendorBillDecisions.actor();
        String justification = VendorBillDecisions.required(command.justification(), "justification");
        VendorBillPostingService.Classification proposed = requireExpenseKey(classification(command.classification()));
        DifferenceDecision difference = difference(command.difference());
        return inTransaction(() -> {
            VendorBill bill = lock(billId);
            if (!SUBMITTABLE.contains(bill.getStatus())) {
                throw notApprovable(bill, "sent for approval");
            }
            readyState(bill, "sent for approval");
            readyContent(bill, difference);
            bill.setStatus(VendorBillStatus.AWAITING_APPROVAL);
            bill.setSubmittedBy(actor);
            bill.setSubmittedAt(Instant.now(clock));
            bill.setSubmissionJustification(justification);
            bill.setProposedDebitClass(proposed == null ? null : proposed.debitClass());
            bill.setProposedExpenseMappingKey(proposed == null ? null : proposed.expenseMappingKey());
            if (difference != null) {
                difference.applyTo(bill);
            }
            bill.setModifiedBy(actor);
            bills.save(bill);
            audit(bill, AUDIT_SUBMIT, actor, justification, differenceDetails(bill));
            return reader.read(bill);
        });
    }

    @Override
    public @NonNull VendorBillResponse approve(@NonNull UUID billId, VendorBillCommands.@NonNull Approve command) {
        VendorBillDecisions.require(VendorBillAction.APPROVE);
        String actor = VendorBillDecisions.actor();
        String justification = VendorBillDecisions.optional(command.justification(), "justification");
        String override = VendorBillDecisions.optional(command.overrideJustification(), "overrideJustification");
        VendorBillPostingService.Classification classification = classification(command.classification());
        DifferenceDecision difference = difference(command.difference());
        return approving(AUDIT_APPROVE, () -> {
            VendorBill bill = lock(billId);
            // 2. state
            if (bill.getStatus() != VendorBillStatus.AWAITING_APPROVAL) {
                throw notApprovable(bill, "approved");
            }
            readyState(bill, "approved");
            // 3. tier, 4. creator is not approver: the approve body's justification carries an exception use
            ApApprovalPolicy.Settings settings = policy.forDecision();
            VendorBillReview.RequiredTier tier = requireTier(bill, settings, actor);
            String exception = creatorRule(bill, settings, actor, command.justification(), "justification");
            // 5. content, 6. the posting
            readyContent(bill, difference);
            Decision decision = new Decision(tier, settings, exception);
            approveAndPost(
                    bill,
                    actor,
                    exception == null ? justification : VendorBillDecisions.required(justification, "justification"),
                    classification,
                    difference,
                    override,
                    AUDIT_APPROVE,
                    null,
                    decision);
            return reader.read(bill);
        });
    }

    @Override
    public @NonNull VendorBillResponse reject(@NonNull UUID billId, VendorBillCommands.@NonNull Reject command) {
        VendorBillDecisions.require(VendorBillAction.REJECT);
        String actor = VendorBillDecisions.actor();
        String reason = VendorBillDecisions.required(command.reason(), "reason");
        return inTransaction(() -> {
            VendorBill bill = lock(billId);
            if (bill.getStatus() != VendorBillStatus.AWAITING_APPROVAL) {
                throw notApprovable(bill, "rejected");
            }
            bill.setStatus(VendorBillStatus.REJECTED);
            bill.setRejectedBy(actor);
            bill.setRejectedAt(Instant.now(clock));
            bill.setRejectionReason(reason);
            bill.setModifiedBy(actor);
            bills.save(bill);
            audit(bill, AUDIT_REJECT, actor, reason, null);
            return reader.read(bill);
        });
    }

    @Override
    public @NonNull VendorBillResponse resolveException(
            @NonNull UUID billId, VendorBillCommands.@NonNull ResolveException command) {
        Resolution resolution = Resolution.parse(command.resolutionAction());
        VendorBillDecisions.require(resolution.action);
        String actor = VendorBillDecisions.actor();
        String reason = VendorBillDecisions.required(command.reason(), "reason");
        boolean accept = resolution == Resolution.ACCEPT;
        String override =
                accept ? VendorBillDecisions.optional(command.overrideJustification(), "overrideJustification") : null;
        VendorBillPostingService.Classification classification =
                accept ? classification(command.classification()) : null;
        DifferenceDecision difference = accept ? difference(command.difference()) : null;
        Supplier<VendorBillResponse> work = () -> {
            VendorBill bill = lock(billId);
            if (bill.getStatus() != VendorBillStatus.MATCH_EXCEPTION) {
                throw notApprovable(bill, "resolved as a match exception");
            }
            switch (resolution) {
                case ACCEPT -> {
                    readyState(bill, "accepted");
                    ApApprovalPolicy.Settings settings = policy.forDecision();
                    VendorBillReview.RequiredTier tier = requireTier(bill, settings, actor);
                    // ACCEPT's required reason is the justification of a creator exception (ruling 3).
                    String exception = creatorRule(bill, settings, actor, reason, "reason");
                    readyContent(bill, difference);
                    approveAndPost(
                            bill,
                            actor,
                            reason,
                            classification,
                            difference,
                            override,
                            AUDIT_RESOLVE,
                            "ACCEPT",
                            new Decision(tier, settings, exception));
                }
                case CORRECT -> correct(bill, actor, reason);
                case VOID -> {
                    // Never posted (AW42): nothing to reverse. Not an approval: no tier, no creator rule.
                    markVoided(bill, actor, reason);
                    audit(bill, AUDIT_RESOLVE, actor, reason, "action=VOID");
                }
            }
            return reader.read(bill);
        };
        return accept ? approving(AUDIT_RESOLVE, work) : inTransaction(work);
    }

    @Override
    public @NonNull VendorBillResponse selectCandidate(@NonNull UUID candidateId) {
        VendorBillDecisions.require(VendorBillAction.SELECT_CANDIDATE);
        String actor = VendorBillDecisions.actor();
        return inTransaction(() -> {
            UUID invoiceEventId = candidates
                    .findById(candidateId)
                    .map(VendorBillMatchCandidate::getInvoiceEventId)
                    .orElseThrow(() -> new VendorBillException(
                            VendorBillException.Code.AP_MATCH_CANDIDATE_NOT_FOUND, "Match candidate not found"));
            // The whole candidate set, locked in id order, then the selection re-read under the lock.
            List<VendorBillMatchCandidate> set = candidates.lockByInvoiceEventId(invoiceEventId);
            VendorBillMatchCandidate selected = set.stream()
                    .filter(c -> c.getCandidateId().equals(candidateId))
                    .findFirst()
                    .orElseThrow(() -> new VendorBillException(
                            VendorBillException.Code.AP_MATCH_CANDIDATE_NOT_FOUND, "Match candidate not found"));
            if (selected.isResolved()) {
                throw new VendorBillException(
                        VendorBillException.Code.AP_MATCH_CANDIDATE_ALREADY_RESOLVED,
                        "This ambiguous match was already resolved by " + selected.getResolvedBy());
            }
            // Then every bill the open candidates name, in id order, each seen as it is now (#2509 review, B-MAJ3).
            List<VendorBill> named = locks.lockAll(set.stream()
                    .filter(c -> !c.isResolved())
                    .map(VendorBillMatchCandidate::getVendorBillId)
                    .toList());
            VendorBill bill = named.stream()
                    .filter(b -> b.getVendorBillId().equals(selected.getVendorBillId()))
                    .findFirst()
                    .orElseThrow(VendorBillApprovalServiceImpl::notFound);
            if (!SUBMITTABLE.contains(bill.getStatus())) {
                throw notApprovable(bill, "selected for this invoice");
            }
            Instant now = Instant.now(clock);
            long open = set.stream().filter(c -> !c.isResolved()).count();
            LocalDateTime dueBefore = bill.getDueDate();
            keepWhatWasBilled(bill, selected, actor);
            for (VendorBillMatchCandidate candidate : set) {
                if (!candidate.isResolved()) {
                    candidate.setResolved(true);
                    candidate.setResolvedBy(actor);
                    candidate.setResolvedAt(now);
                    candidate.setSelected(candidate.getCandidateId().equals(candidateId));
                    candidates.save(candidate);
                }
            }
            // Selection is matching only: the bill goes to approval, nothing approves it (§7.1).
            bill.setStatus(VendorBillStatus.AWAITING_APPROVAL);
            bill.setRejectionReason(null);
            bill.setSubmittedBy(actor);
            bill.setSubmittedAt(now);
            bill.setSubmissionJustification("Selected among " + open + " candidates of an ambiguous match (score "
                    + selected.getMatchScore() + ")");
            bill.setModifiedBy(actor);
            bills.save(bill);
            audit(
                    bill,
                    AUDIT_SELECT,
                    actor,
                    null,
                    "candidateId=" + candidateId + ";candidates=" + open + dueDateChange(dueBefore, bill.getDueDate()));
            named.stream()
                    .filter(b -> !b.getVendorBillId().equals(bill.getVendorBillId()))
                    .forEach(other -> release(other, invoiceEventId, actor));
            return reader.read(bill);
        });
    }

    @Override
    public @NonNull VendorBillResponse voidBill(@NonNull UUID billId, VendorBillCommands.@NonNull VoidBill command) {
        // Every void needs accounting:ap:reject; an approved bill's needs the approval tier too, checked below.
        VendorBillDecisions.require(VendorBillAction.VOID_UNMATCHED);
        String actor = VendorBillDecisions.actor();
        String reason = VendorBillDecisions.required(command.reason(), "reason");
        String override = VendorBillDecisions.optional(command.overrideJustification(), "overrideJustification");
        return approving(AUDIT_VOID, () -> {
            VendorBill bill = lock(billId);
            if (bill.getStatus() == VendorBillStatus.APPROVED) {
                voidApproved(bill, actor, reason, override);
            } else if (bill.getStatus() == VendorBillStatus.PENDING_RECEIPT_MATCH
                    && VendorBillReader.channelOf(bill) == VendorBillReview.Channel.GOODS_RECEIPT) {
                // AW45: the receipt's placeholder closed. Nothing posts: the receipt's accrual stays in 2100 until the
                // vendor's EDI bill, classified GOODS, clears it at its approval.
                markVoided(bill, actor, reason);
                audit(bill, AUDIT_VOID, actor, reason, "action=VOID_UNMATCHED;posted=none");
            } else {
                throw new VendorBillException(
                        VendorBillException.Code.AP_BILL_NOT_VOIDABLE,
                        "Bill " + bill.getBillNumber() + " is " + bill.getStatus() + "; only an APPROVED bill, or a"
                                + " goods-receipt bill in PENDING_RECEIPT_MATCH, is voided this way (a bill in"
                                + " MATCH_EXCEPTION is voided by resolve-exception VOID, one awaiting approval is"
                                + " rejected)");
            }
            return reader.read(bill);
        });
    }

    /**
     * The void of an approved bill (AW42), in the guard order: the permission (ap:reject and either approve
     * permission), the state (nothing allocated), then the tier against the current limit (ruling 4). The creator rule
     * does not apply to a void.
     */
    private void voidApproved(VendorBill bill, String actor, String reason, @Nullable String override) {
        VendorBillDecisions.require(VendorBillAction.VOID_APPROVED);
        if (allocations.existsByVendorBill_VendorBillId(bill.getVendorBillId())) {
            throw new VendorBillException(
                    VendorBillException.Code.AP_BILL_NOT_VOIDABLE,
                    "Bill " + bill.getBillNumber() + " has payments allocated to it; correct it with a vendor"
                            + " credit note");
        }
        ApApprovalPolicy.Settings settings = policy.forDecision();
        VendorBillReview.RequiredTier tier = requireTier(bill, settings, actor);
        VendorBillGlPosting posting = postingService.reverse(bill, override, actor);
        markVoided(bill, actor, reason);
        audit(
                bill,
                AUDIT_VOID,
                actor,
                reason,
                "action=VOID_APPROVED;reversalJournalEntryId=" + posting.getReversalJournalEntryId() + ";voidDate="
                        + posting.getReversalDate() + (override == null ? "" : ";periodOverride=true"),
                new Decision(tier, settings, null));
    }

    // ---- the real due date (S13, §4.2, AW11) ----------------------------------------------------------------

    /** The statuses of approval review, in which a person may enter the real due date. */
    static final Set<VendorBillStatus> REVIEW_STATUSES = VendorBillReader.REVIEW_STATUSES;

    @Override
    public @NonNull VendorBillResponse setDueDate(
            @NonNull UUID billId, VendorBillCommands.@NonNull SetDueDate command) {
        VendorBillDecisions.require(VendorBillAction.SET_DUE_DATE);
        String actor = VendorBillDecisions.actor();
        if (command.dueDate() == null) {
            throw new VendorBillException(
                    VendorBillException.Code.VALIDATION_ERROR,
                    "dueDate is required (YYYY-MM-DD)",
                    List.of(new VendorBillException.FieldError("dueDate", "is required")),
                    null);
        }
        String justification = VendorBillDecisions.optional(command.justification(), "justification");
        LocalDateTime dueDate = command.dueDate().atStartOfDay();
        return inTransaction(() -> {
            VendorBill bill = lock(billId);
            if (!REVIEW_STATUSES.contains(bill.getStatus())) {
                // Approved bills are locked; CURRENCY_HOLD never reaches review (ADR-0067).
                throw notApprovable(
                        bill,
                        "given a due date: only a bill in PENDING_RECEIPT_MATCH, MATCH_EXCEPTION or AWAITING_APPROVAL"
                                + " takes one");
            }
            LocalDateTime before = bill.getDueDate();
            if (dueDate.equals(before)) {
                return reader.read(bill);
            }
            bill.setDueDate(dueDate);
            bill.setModifiedBy(actor);
            bills.save(bill);
            AccountingAuditLog row = auditRow(
                    bill,
                    AUDIT_DUE_DATE_SET,
                    actor,
                    justification,
                    "dueDate=" + dueDate.toLocalDate(),
                    decisionNow(bill));
            row.setOldValue(before == null ? null : before.toLocalDate().toString());
            auditLogs.save(row);
            return reader.read(bill);
        });
    }

    private void markVoided(VendorBill bill, String actor, String reason) {
        bill.setStatus(VendorBillStatus.VOIDED);
        bill.setRejectedBy(actor);
        bill.setRejectedAt(Instant.now(clock));
        bill.setRejectionReason(reason);
        bill.setModifiedBy(actor);
        bills.save(bill);
    }

    /**
     * Resolve-exception {@code CORRECT} (#2509 review, B-MAJ2): not an approval, no approval or rejection field is
     * written. A goods-receipt bill goes back to its receipt, as if no invoice had been matched: the billed lines and
     * total are undone, and the bill date and number are the receipt's again (L-new-1: the invoice number the match
     * gave it no longer names it, so an EDI fact under that number never finds this bill as its live original). The
     * next match compares the invoice with what was received. Whatever was proposed for approval is cleared with it.
     */
    private void correct(VendorBill bill, String actor, String reason) {
        if (VendorBillReader.channelOf(bill) == VendorBillReview.Channel.GOODS_RECEIPT) {
            matcher.restoreReceived(bill);
            evidence.findFirstByVendorBillIdOrderByRecordedAtDescMatchEvidenceIdDesc(bill.getVendorBillId())
                    .ifPresent(received -> {
                        bill.setBillDate(received.getReceivedDate());
                        bill.setBillNumber(received.getReceivedBillNumber());
                    });
        }
        bill.setStatus(VendorBillStatus.PENDING_RECEIPT_MATCH);
        bill.setRejectionReason(null);
        bill.clearSubmission();
        bill.setModifiedBy(actor);
        bills.save(bill);
        audit(bill, AUDIT_RESOLVE, actor, reason, "action=CORRECT");
    }

    /**
     * Another bill of the same ambiguous match after the selection (#2509 review): the top bill the match had put in
     * {@code MATCH_EXCEPTION} for it goes back to {@code PENDING_RECEIPT_MATCH}, to wait for its own invoice. A bill
     * someone moved on meanwhile is left as it is.
     */
    private void release(VendorBill other, UUID invoiceEventId, String actor) {
        if (other.getStatus() != VendorBillStatus.MATCH_EXCEPTION) {
            return;
        }
        boolean heldForThisMatch = evidence.findFirstByVendorBillIdOrderByRecordedAtDescMatchEvidenceIdDesc(
                        other.getVendorBillId())
                .filter(VendorBillReader::ambiguousScoring)
                .filter(row -> invoiceEventId.equals(row.getInvoiceEventId()))
                .isPresent();
        if (!heldForThisMatch) {
            return;
        }
        other.setStatus(VendorBillStatus.PENDING_RECEIPT_MATCH);
        other.setRejectionReason(null);
        other.setModifiedBy(actor);
        bills.save(other);
        audit(other, AUDIT_RELEASE, actor, null, "invoiceEventId=" + invoiceEventId);
    }

    // ---- reads ----------------------------------------------------------------------------------------------

    @Override
    public @NonNull VendorBillResponse getBill(@NonNull UUID billId) {
        return Objects.requireNonNull(commandTransaction.execute(
                _ -> reader.read(bills.findById(billId).orElseThrow(VendorBillApprovalServiceImpl::notFound))));
    }

    @Override
    public VendorBillReview.@NonNull StageCounts stageCounts() {
        return reader.stageCounts();
    }

    @Override
    public @NonNull Page<VendorBillReview.StageRow> listByStage(@NonNull VendorBillStage stage, int page, int size) {
        return reader.byStage(stage, page, size);
    }

    // ---- approval and posting -------------------------------------------------------------------------------

    /**
     * Posts the bill, then writes the approval (AW37): a bill is approved if and only if it posted. A refusal of the
     * posting leaves the bill as it was and propagates, carried by {@link PostingRefused} so the refusal is audited
     * after the rollback. The classification given is merged with the one proposed at submission, field by field; a
     * difference given replaces the one proposed.
     */
    private void approveAndPost(
            VendorBill bill,
            String actor,
            @Nullable String justification,
            VendorBillPostingService.@Nullable Classification classification,
            @Nullable DifferenceDecision difference,
            @Nullable String override,
            String operation,
            @Nullable String resolution,
            Decision decision) {
        VendorBillPostingService.Classification effective = requireExpenseKey(merge(classification, bill));
        if (difference != null) {
            difference.applyTo(bill);
        }
        VendorBillGlPosting posting;
        try {
            posting = postingService.post(bill, effective, override, actor);
        } catch (RuntimeException refused) {
            throw new PostingRefused(bill.getVendorBillId(), bill.getBillNumber(), actor, refused);
        }
        bill.setStatus(VendorBillStatus.APPROVED);
        bill.setRejectionReason(null);
        bill.setApprovedBy(actor);
        bill.setApprovedByKind(VendorBillApproverKind.PERSON);
        bill.setApprovedAt(Instant.now(clock));
        bill.setApprovalJustification(justification);
        bill.setModifiedBy(actor);
        bills.save(bill);
        String details = differenceDetails(bill);
        audit(
                bill,
                operation,
                actor,
                justification,
                (resolution == null ? "" : "action=" + resolution + ";") + "journalEntryId="
                        + posting.getJournalEntryId() + ";postingDate=" + posting.getPostingDate()
                        + ";postingDateRule=" + posting.getPostingDateRule() + ";roundingAdjustment="
                        + posting.getRoundingAdjustment().toPlainString()
                        + (details == null ? "" : ";" + details)
                        + (override == null ? "" : ";periodOverride=true"),
                decision);
        if (decision.exception() != null) {
            audit(bill, AUDIT_SOD_EXCEPTION, actor, justification, "operation=" + operation, decision);
        }
    }

    /**
     * Guard step 2, the bill's state (#2509 review; AW45; S13 ruling 1), refused with 409 before anything is written:
     * no open ambiguous match naming the bill, and a matched invoice for a goods-receipt bill. An AW45 bill has no
     * billed total yet, so its tier means nothing: this runs before {@link #requireTier}.
     */
    private void readyState(VendorBill bill, String what) {
        if (reader.hasOpenCandidates(bill.getVendorBillId())) {
            throw new VendorBillException(
                    VendorBillException.Code.AP_BILL_NOT_APPROVABLE,
                    "Bill " + bill.getBillNumber() + " is a candidate of an ambiguous match nobody has picked yet;"
                            + " pick the match first (select a candidate), then it can be " + what);
        }
        if (VendorBillReader.channelOf(bill) == VendorBillReview.Channel.GOODS_RECEIPT
                && !reader.invoiceMatched(bill)) {
            throw new VendorBillException(
                    VendorBillException.Code.AP_BILL_AWAITING_INVOICE,
                    "Bill " + bill.getBillNumber() + " is a goods receipt no vendor invoice has been matched to; match"
                            + " the invoice (POST /v1/accounting/vendor-bills/match) or select a candidate before it"
                            + " is " + what + ", or void the bill if no invoice will come");
        }
    }

    /**
     * Guard step 5, the bill's content (#2509 review; AW47), refused with 422 before anything is written: a total that
     * is not 0.00, and the vendor's totals adding up or a {@code difference} decided. S43 adds the tax-on-resale check
     * ({@code AP_BILL_TAX_ON_RESALE_GOODS}) at the end of this step.
     */
    private void readyContent(VendorBill bill, @Nullable DifferenceDecision difference) {
        if (bill.getTotalAmount() == null || bill.getTotalAmount().signum() == 0) {
            throw new VendorBillException(
                    VendorBillException.Code.AP_BILL_ZERO_TOTAL,
                    "Bill " + bill.getBillNumber() + " totals 0.00; there is nothing to approve or post. Correct it"
                            + " or void it");
        }
        VendorBillPostingService.requireReconciled(
                bill, difference != null ? difference.difference() : VendorBillPostingService.difference(bill));
    }

    /**
     * A decision refused by its posting (AW42), its tier or the creator rule (S13), carried out of the rolled-back
     * transaction to be audited.
     */
    private static final class PostingRefused extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final UUID billId;
        private final String billNumber;
        private final String actor;
        private final @Nullable String details;

        PostingRefused(UUID billId, String billNumber, String actor, RuntimeException cause) {
            this(billId, billNumber, actor, cause, null);
        }

        PostingRefused(UUID billId, String billNumber, String actor, RuntimeException cause, @Nullable String details) {
            super(cause);
            this.billId = billId;
            this.billNumber = billNumber;
            this.actor = actor;
            this.details = details;
        }

        @Override
        public synchronized @NonNull RuntimeException getCause() {
            return (RuntimeException) super.getCause();
        }
    }

    /**
     * Runs a deciding command; a refusal carried as {@link PostingRefused} (the posting, the tier, the creator rule) is
     * audited in its own transaction, then rethrown as it was.
     */
    private VendorBillResponse approving(String operation, Supplier<VendorBillResponse> work) {
        try {
            return inTransaction(work);
        } catch (PostingRefused refused) {
            refusalTransaction.executeWithoutResult(_ -> auditRefusal(operation, refused));
            throw refused.getCause();
        }
    }

    private void auditRefusal(String operation, PostingRefused refused) {
        AccountingAuditLog row = new AccountingAuditLog();
        row.setEntityType(AUDIT_ENTITY_TYPE);
        row.setEntityId(refused.billId);
        row.setOperation(operation + AUDIT_REFUSED_SUFFIX);
        row.setUserId(refused.actor);
        row.setNewValue(truncate(
                "billNumber=" + refused.billNumber + ";code=" + codeOf(refused.getCause())
                        + (refused.details == null ? "" : ";" + refused.details) + ";message="
                        + refused.getCause().getMessage(),
                2000));
        auditLogs.save(row);
        log.info(
                "{} of vendor bill {} refused ({}); the bill is unchanged",
                operation,
                refused.billNumber,
                codeOf(refused.getCause()));
    }

    /** The stable code of a refusal, for an audit row. */
    static String codeOf(RuntimeException cause) {
        if (cause instanceof VendorBillAutoApproval.Skip skip && skip.code() != null) {
            return skip.code();
        }
        if (cause instanceof VendorBillException vendorBill) {
            return vendorBill.getCode().name();
        }
        if (cause instanceof AccountingPeriodHardLockedException) {
            return "PERIOD_HARD_LOCKED";
        }
        if (cause instanceof AccountingPeriodClosedException) {
            return "PERIOD_CLOSED";
        }
        if (cause instanceof GLMappingNotConfiguredException) {
            return "GL_MAPPING_NOT_CONFIGURED";
        }
        return cause.getClass().getSimpleName();
    }

    // ---- candidate selection --------------------------------------------------------------------------------

    /**
     * Candidate selection keeps what the vendor billed, as {@code /match} does (AW39, AW46): the billed lines and
     * total, the vendor's invoice number, its date as the bill date and its due date, and the evidence with the
     * receipt date. The duplicate rule is checked first on the invoice's number and date, so a refusal changes
     * nothing. A candidate scored before #2509 kept no invoice: it cannot be selected (409 {@code
     * AP_BILL_AWAITING_INVOICE}); the invoice is matched again instead.
     */
    private void keepWhatWasBilled(VendorBill bill, VendorBillMatchCandidate selected, String actor) {
        if (selected.getInvoiceReference() == null || selected.getInvoiceDate() == null) {
            throw new VendorBillException(
                    VendorBillException.Code.AP_BILL_AWAITING_INVOICE,
                    "This candidate was scored before #2509 and kept no invoice; match the vendor invoice again"
                            + " (POST /v1/accounting/vendor-bills/match)");
        }
        duplicateGuard.refuseIfDuplicate(
                VendorBillDuplicateGuard.Channel.MATCH,
                bill.getVendorId(),
                selected.getInvoiceReference(),
                selected.getInvoiceDate(),
                bill.getVendorBillId());
        LocalDateTime receivedDate = bill.getBillDate();
        String receivedBillNumber = bill.getBillNumber();
        VendorBillInvoiceMatcher.Comparison comparison =
                matcher.applyBilled(bill, VendorBillInvoiceMatcher.fromJson(selected.getInvoiceLines()));
        bill.setBillNumber(selected.getInvoiceReference());
        bill.setBillDate(selected.getInvoiceDate());
        if (selected.getInvoiceDueDate() != null) {
            bill.setDueDate(selected.getInvoiceDueDate());
        }
        matcher.record(
                bill,
                selected.getInvoiceEventId(),
                VendorBillMatchEvidence.Source.CANDIDATE_SELECTION,
                MatchConfidence.AMBIGUOUS,
                new VendorBillInvoiceMatcher.Points(
                        nz(selected.getAmountPoints()),
                        nz(selected.getProductPoints()),
                        nz(selected.getDatePoints()),
                        nz(selected.getPurchaseOrderPoints())),
                selected.getInvoiceReference(),
                selected.getInvoiceDate(),
                receivedDate,
                receivedBillNumber,
                comparison,
                actor);
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private VendorBillResponse inTransaction(Supplier<VendorBillResponse> work) {
        return Objects.requireNonNull(commandTransaction.execute(_ -> work.get()));
    }

    private VendorBill lock(UUID billId) {
        return bills.lockById(billId).orElseThrow(VendorBillApprovalServiceImpl::notFound);
    }

    private static VendorBillException notFound() {
        // ADR-0017: a bill of another tenant is invisible under row-level security; its id is never echoed.
        return new VendorBillException(VendorBillException.Code.VENDOR_BILL_NOT_FOUND, "Vendor bill not found");
    }

    private static VendorBillException notApprovable(VendorBill bill, String what) {
        return new VendorBillException(
                VendorBillException.Code.AP_BILL_NOT_APPROVABLE,
                "Bill " + bill.getBillNumber() + " is " + bill.getStatus() + " and cannot be " + what);
    }

    /** The request's classification, its shape checked (400 VALIDATION_ERROR); null when none is given. */
    static VendorBillPostingService.@Nullable Classification classification(
            VendorBillReview.@Nullable Classification given) {
        if (given == null || (given.debitClass() == null && isBlank(given.expenseMappingKey()))) {
            return null;
        }
        if (given.debitClass() == VendorBillDebitClass.RECEIPT_MATCHED) {
            throw new VendorBillException(
                    VendorBillException.Code.VALIDATION_ERROR,
                    "classification.debitClass RECEIPT_MATCHED is never given: matched lines class themselves");
        }
        return new VendorBillPostingService.Classification(
                given.debitClass(), expenseKey(given.expenseMappingKey(), "classification.expenseMappingKey"));
    }

    /**
     * The classification an approval posts with (#2509 review, LOW-12): each field given wins, an absent one is the
     * proposal's; null when neither says anything.
     */
    static VendorBillPostingService.@Nullable Classification merge(
            VendorBillPostingService.@Nullable Classification given, @NonNull VendorBill bill) {
        VendorBillDebitClass debitClass =
                given != null && given.debitClass() != null ? given.debitClass() : bill.getProposedDebitClass();
        String key = given != null && given.expenseMappingKey() != null
                ? given.expenseMappingKey()
                : bill.getProposedExpenseMappingKey();
        return debitClass == null && key == null ? null : new VendorBillPostingService.Classification(debitClass, key);
    }

    /** An EXPENSE classification names its key (400 VALIDATION_ERROR). */
    private static VendorBillPostingService.@Nullable Classification requireExpenseKey(
            VendorBillPostingService.@Nullable Classification classification) {
        if (classification != null
                && classification.debitClass() == VendorBillDebitClass.EXPENSE
                && classification.expenseMappingKey() == null) {
            throw new VendorBillException(
                    VendorBillException.Code.VALIDATION_ERROR,
                    "classification.expenseMappingKey is required with debitClass EXPENSE");
        }
        return classification;
    }

    /**
     * The request's difference decision (AW47), its shape checked: a class (400 VALIDATION_ERROR), an expense key for
     * EXPENSE, and a justification of at least 10 characters (400 JUSTIFICATION_REQUIRED). Null when none is given.
     */
    static @Nullable DifferenceDecision difference(VendorBillReview.@Nullable Difference given) {
        if (given == null) {
            return null;
        }
        if (given.differenceClass() == null) {
            throw new VendorBillException(
                    VendorBillException.Code.VALIDATION_ERROR,
                    "difference.class is required: FREIGHT, GOODS, EXPENSE or PRICE_DIFFERENCE");
        }
        String key = expenseKey(given.expenseMappingKey(), "difference.expenseMappingKey");
        if (given.differenceClass() == VendorBillDifferenceClass.EXPENSE && key == null) {
            throw new VendorBillException(
                    VendorBillException.Code.VALIDATION_ERROR,
                    "difference.expenseMappingKey is required with class EXPENSE");
        }
        String justification = VendorBillDecisions.required(given.justification(), "difference.justification");
        return new DifferenceDecision(
                new VendorBillPostingService.Difference(
                        given.differenceClass(),
                        given.differenceClass() == VendorBillDifferenceClass.EXPENSE ? key : null),
                justification);
    }

    /** An expense key as given, upper-cased; it must be a {@code VENDOR_BILL} key {@code EXPENSE_<CODE>}. */
    private static @Nullable String expenseKey(@Nullable String given, String field) {
        if (isBlank(given)) {
            return null;
        }
        String key = given.trim().toUpperCase(Locale.ROOT);
        if (!key.startsWith(VendorBillPostingService.EXPENSE_KEY_PREFIX)) {
            throw new VendorBillException(
                    VendorBillException.Code.VALIDATION_ERROR,
                    field + " must be a VENDOR_BILL expense key, EXPENSE_<CODE>");
        }
        return key;
    }

    /** A decided difference (AW47) with its justification, stored on the bill until it posts. */
    record DifferenceDecision(
            VendorBillPostingService.@NonNull Difference difference,
            @NonNull String justification) {

        void applyTo(VendorBill bill) {
            bill.setDifferenceClass(difference.differenceClass());
            bill.setDifferenceExpenseMappingKey(difference.expenseMappingKey());
            bill.setDifferenceJustification(justification);
        }
    }

    private static @Nullable String differenceDetails(VendorBill bill) {
        if (bill.getDifferenceClass() == null) {
            return null;
        }
        return "differenceClass=" + bill.getDifferenceClass()
                + (bill.getDifferenceExpenseMappingKey() == null
                        ? ""
                        : ";differenceExpenseMappingKey=" + bill.getDifferenceExpenseMappingKey())
                + VendorBillTotals.of(bill)
                        .map(t -> ";difference=" + t.difference().toPlainString())
                        .orElse("");
    }

    // ---- the tier and the creator rule (S13) -------------------------------------------------------------

    /**
     * The tier, the limits and the exception switch a decision used, for its audit row (S13, #2510).
     *
     * @param exception {@value #EXCEPTION_CREATOR_APPROVAL} when the creator approved under the switch, else null
     */
    record Decision(
            VendorBillReview.@NonNull RequiredTier tier,
            ApApprovalPolicy.@NonNull Settings settings,
            @Nullable String exception) {

        String auditText() {
            return "tier=" + tier + ";limit=" + settings.clerkApprovalLimit().toPlainString() + ";autoLimit="
                    + settings.autoApprovalLimit().toPlainString() + ";exception="
                    + (exception == null ? EXCEPTION_NONE : exception);
        }
    }

    /** The tier the bill needs under the policy in force now, for a row that is not a decision (submit, reject). */
    private Decision decisionNow(VendorBill bill) {
        ApApprovalPolicy.Settings settings = policy.settings();
        return new Decision(settings.tier(bill.getTotalAmount()), settings, null);
    }

    /**
     * Guard step 3 (S13, #2510; AW4, AW5): the bill's tier against the current clerk limit. An {@code OVER_LIMIT} bill
     * needs {@code accounting:ap:approve_over_limit}; otherwise 403 {@code AP_APPROVAL_LIMIT_EXCEEDED}, naming the
     * total and the limit, audited in its own transaction.
     */
    private VendorBillReview.RequiredTier requireTier(
            VendorBill bill, ApApprovalPolicy.Settings settings, String actor) {
        VendorBillReview.RequiredTier tier = settings.tier(bill.getTotalAmount());
        if (VendorBillDecisions.mayDecideTier(tier)) {
            return tier;
        }
        String currency = currencyOf(bill);
        String total =
                bill.getTotalAmount() == null ? "0.00" : bill.getTotalAmount().toPlainString();
        String limit = settings.clerkApprovalLimit().toPlainString();
        VendorBillException refusal = new VendorBillException(
                VendorBillException.Code.AP_APPROVAL_LIMIT_EXCEEDED,
                "Bill " + bill.getBillNumber() + " totals " + total + " " + currency + ", over the clerk approval limit"
                        + " of " + limit + " " + currency + "; an approver over the limit decides it",
                List.of(),
                "Ask a holder of " + AccountingPermissions.AP_APPROVE_OVER_LIMIT + " (a CONTROLLER or GENERAL_MANAGER)"
                        + " to decide this bill");
        throw new PostingRefused(
                bill.getVendorBillId(),
                bill.getBillNumber(),
                actor,
                refusal,
                new Decision(tier, settings, null).auditText() + ";totalAmount=" + total + ";currencyCode=" + currency);
    }

    /**
     * Guard step 4, separation of duties 1 (§4.3, AW6; S13 rulings 1 and 3): the bill's creator may not approve or
     * accept it, 403 {@code AP_BILL_SELF_APPROVAL} audited in its own transaction. Under {@code
     * AP_ALLOW_CREATOR_APPROVAL} the decision goes through as an exception use, which needs a justification of at least
     * 10 characters ({@code field}: approve's {@code justification}, {@code ACCEPT}'s {@code reason}). A bill a system
     * created ({@code supplier}, {@code SYSTEM}) never matches a person.
     *
     * @return {@value #EXCEPTION_CREATOR_APPROVAL} when the exception is used, else null
     */
    private @Nullable String creatorRule(
            VendorBill bill,
            ApApprovalPolicy.Settings settings,
            String actor,
            @Nullable String justification,
            String field) {
        if (!actor.equals(bill.getCreatedBy())) {
            return null;
        }
        if (!settings.allowCreatorApproval()) {
            throw new PostingRefused(
                    bill.getVendorBillId(),
                    bill.getBillNumber(),
                    actor,
                    new VendorBillException(
                            VendorBillException.Code.AP_BILL_SELF_APPROVAL,
                            "You created bill " + bill.getBillNumber() + "; another person approves it",
                            List.of(),
                            "Ask another approver to decide this bill"),
                    "createdBy=" + bill.getCreatedBy());
        }
        VendorBillDecisions.required(justification, field);
        return EXCEPTION_CREATOR_APPROVAL;
    }

    private String currencyOf(VendorBill bill) {
        return bill.getCurrency() == null || bill.getCurrency().isBlank()
                ? ledgerCurrency.code()
                : bill.getCurrency().trim();
    }

    /** {@code ;dueDate=OLD->NEW} when a match or a selection replaced the due date (ruling 7), else empty. */
    static String dueDateChange(@Nullable LocalDateTime before, @Nullable LocalDateTime after) {
        if (Objects.equals(before, after)) {
            return "";
        }
        return ";dueDate=" + (before == null ? "" : before.toLocalDate()) + "->"
                + (after == null ? "" : after.toLocalDate());
    }

    private void audit(
            VendorBill bill, String operation, String actor, @Nullable String justification, @Nullable String details) {
        audit(bill, operation, actor, justification, details, decisionNow(bill));
    }

    private void audit(
            VendorBill bill,
            String operation,
            String actor,
            @Nullable String justification,
            @Nullable String details,
            Decision decision) {
        auditLogs.save(auditRow(bill, operation, actor, justification, details, decision));
    }

    private AccountingAuditLog auditRow(
            VendorBill bill,
            String operation,
            String actor,
            @Nullable String justification,
            @Nullable String details,
            Decision decision) {
        VendorBillMatchEvidence latest = evidence.findFirstByVendorBillIdOrderByRecordedAtDescMatchEvidenceIdDesc(
                        bill.getVendorBillId())
                .orElse(null);
        AccountingAuditLog row = new AccountingAuditLog();
        row.setEntityType(AUDIT_ENTITY_TYPE);
        row.setEntityId(bill.getVendorBillId());
        row.setOperation(operation);
        row.setUserId(actor);
        row.setJustification(justification == null ? null : truncate(justification, 1000));
        row.setNewValue(truncate(auditText(bill, latest, currencyOf(bill), decision, details), 2000));
        return row;
    }

    /** The {@code new_value} of a decision row: the bill, the tier and limits in force, the match, the details. */
    static String auditText(
            VendorBill bill,
            @Nullable VendorBillMatchEvidence latest,
            String currency,
            Decision decision,
            @Nullable String details) {
        return "billNumber=" + bill.getBillNumber() + ";status=" + bill.getStatus() + ";" + decision.auditText()
                + ";totalAmount="
                + (bill.getTotalAmount() == null ? "" : bill.getTotalAmount().toPlainString())
                + ";currencyCode=" + currency + ";matchScore="
                + (latest == null ? "" : String.valueOf(latest.getScore())) + ";evidenceId="
                + (latest == null ? "" : latest.getMatchEvidenceId())
                + (details == null ? "" : ";" + details);
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    private static int nz(@Nullable Integer value) {
        return value == null ? 0 : value;
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** The resolution actions of a match exception, each with the decision it is. */
    private enum Resolution {
        ACCEPT(VendorBillAction.ACCEPT_EXCEPTION),
        CORRECT(VendorBillAction.CORRECT_EXCEPTION),
        VOID(VendorBillAction.VOID_EXCEPTION);

        private final VendorBillAction action;

        Resolution(VendorBillAction action) {
            this.action = action;
        }

        static Resolution parse(@Nullable String value) {
            if (value != null) {
                for (Resolution resolution : values()) {
                    if (resolution.name().equalsIgnoreCase(value.trim())) {
                        return resolution;
                    }
                }
            }
            throw new VendorBillException(
                    VendorBillException.Code.VALIDATION_ERROR, "resolutionAction must be ACCEPT, CORRECT or VOID");
        }
    }
}
