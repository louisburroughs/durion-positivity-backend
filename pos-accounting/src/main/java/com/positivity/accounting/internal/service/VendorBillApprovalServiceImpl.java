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
import java.io.Serial;
import java.math.BigDecimal;
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
 * <p><b>Audit.</b> One {@code accounting_audit_log} row per decision (entity type {@value #AUDIT_ENTITY_TYPE}): the
 * actor, the tier used ({@code OVER_LIMIT}), the limit at that moment (0, the specification's default until S13
 * records the real one), the bill's total and currency, the latest match score and evidence id, the justification
 * and, for an approval, the entry and its date.
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

    /** Suffix of the audit operation that records an approval refused by its posting (AW42). */
    static final String AUDIT_REFUSED_SUFFIX = "_REFUSED";

    /** The clerk limit in force until S13 stores one: the specification's default (§4.3, "unset = 0"). */
    static final BigDecimal CLERK_LIMIT_DEFAULT = BigDecimal.ZERO;

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
            readyToDecide(bill, difference, "sent for approval");
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
            if (bill.getStatus() != VendorBillStatus.AWAITING_APPROVAL) {
                throw notApprovable(bill, "approved");
            }
            readyToDecide(bill, difference, "approved");
            approveAndPost(bill, actor, justification, classification, difference, override, AUDIT_APPROVE, null);
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
                    readyToDecide(bill, difference, "accepted");
                    approveAndPost(bill, actor, reason, classification, difference, override, AUDIT_RESOLVE, "ACCEPT");
                }
                case CORRECT -> correct(bill, actor, reason);
                case VOID -> {
                    // Never posted (AW42): nothing to reverse.
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
            audit(bill, AUDIT_SELECT, actor, null, "candidateId=" + candidateId + ";candidates=" + open);
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
        return inTransaction(() -> {
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

    private void voidApproved(VendorBill bill, String actor, String reason, @Nullable String override) {
        VendorBillDecisions.require(VendorBillAction.VOID_APPROVED);
        if (allocations.existsByVendorBill_VendorBillId(bill.getVendorBillId())) {
            throw new VendorBillException(
                    VendorBillException.Code.AP_BILL_NOT_VOIDABLE,
                    "Bill " + bill.getBillNumber() + " has payments allocated to it; correct it with a vendor"
                            + " credit note");
        }
        VendorBillGlPosting posting = postingService.reverse(bill, override, actor);
        markVoided(bill, actor, reason);
        audit(
                bill,
                AUDIT_VOID,
                actor,
                reason,
                "action=VOID_APPROVED;reversalJournalEntryId=" + posting.getReversalJournalEntryId() + ";voidDate="
                        + posting.getReversalDate() + (override == null ? "" : ";periodOverride=true"));
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
            @Nullable String resolution) {
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
                        + (override == null ? "" : ";periodOverride=true"));
    }

    /**
     * What every send, approval and acceptance needs first (#2509 review; AW45, AW47), refused before anything is
     * written: no open ambiguous match naming the bill, a matched invoice for a goods-receipt bill, a total that is
     * not 0.00, and the vendor's totals adding up or a {@code difference} decided.
     */
    private void readyToDecide(VendorBill bill, @Nullable DifferenceDecision difference, String what) {
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
        if (bill.getTotalAmount() == null || bill.getTotalAmount().signum() == 0) {
            throw new VendorBillException(
                    VendorBillException.Code.AP_BILL_ZERO_TOTAL,
                    "Bill " + bill.getBillNumber() + " totals 0.00; there is nothing to approve or post. Correct it"
                            + " or void it");
        }
        VendorBillPostingService.requireReconciled(
                bill, difference != null ? difference.difference() : VendorBillPostingService.difference(bill));
    }

    /** A posting refused at approval, carried out of the rolled-back transaction to be audited. */
    private static final class PostingRefused extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final UUID billId;
        private final String billNumber;
        private final String actor;

        PostingRefused(UUID billId, String billNumber, String actor, RuntimeException cause) {
            super(cause);
            this.billId = billId;
            this.billNumber = billNumber;
            this.actor = actor;
        }

        @Override
        public synchronized @NonNull RuntimeException getCause() {
            return (RuntimeException) super.getCause();
        }
    }

    /** Runs an approving command; a refused posting is audited in its own transaction, then rethrown as it was. */
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
                "billNumber=" + refused.billNumber + ";code=" + codeOf(refused.getCause()) + ";message="
                        + refused.getCause().getMessage(),
                2000));
        auditLogs.save(row);
        log.info(
                "Approval of vendor bill {} refused by its posting ({}); the bill is unchanged",
                refused.billNumber,
                codeOf(refused.getCause()));
    }

    private static String codeOf(RuntimeException cause) {
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

    private void audit(
            VendorBill bill, String operation, String actor, @Nullable String justification, @Nullable String details) {
        VendorBillMatchEvidence latest = evidence.findFirstByVendorBillIdOrderByRecordedAtDescMatchEvidenceIdDesc(
                        bill.getVendorBillId())
                .orElse(null);
        String currency =
                bill.getCurrency() == null || bill.getCurrency().isBlank() ? ledgerCurrency.code() : bill.getCurrency();
        AccountingAuditLog row = new AccountingAuditLog();
        row.setEntityType(AUDIT_ENTITY_TYPE);
        row.setEntityId(bill.getVendorBillId());
        row.setOperation(operation);
        row.setUserId(actor);
        row.setJustification(justification == null ? null : truncate(justification, 1000));
        row.setNewValue(truncate(
                "billNumber=" + bill.getBillNumber() + ";status=" + bill.getStatus() + ";tier=OVER_LIMIT;limit="
                        + CLERK_LIMIT_DEFAULT.toPlainString() + ";totalAmount="
                        + (bill.getTotalAmount() == null
                                ? ""
                                : bill.getTotalAmount().toPlainString())
                        + ";currencyCode=" + currency + ";matchScore="
                        + (latest == null ? "" : String.valueOf(latest.getScore())) + ";evidenceId="
                        + (latest == null ? "" : latest.getMatchEvidenceId())
                        + (details == null ? "" : ";" + details),
                2000));
        auditLogs.save(row);
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
