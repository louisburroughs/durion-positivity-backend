package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillGlPosting;
import com.positivity.accounting.internal.entity.VendorBillLine;
import com.positivity.accounting.internal.entity.VendorBillMatchEvidence;
import com.positivity.accounting.internal.enums.VendorBillApproverKind;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import java.io.Serial;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Automatic approval of a goods-receipt bill on a HIGH {@code /match} (CAP:550 S13, #2510; SPEC-accounting-workspace
 * §4.3, §7.1 "System approver"; AW37, AW42, AW45-AW47; rulings 6048398147 and 6060589020 item 5).
 *
 * <p><b>When.</b> Only in the match transaction, on a HIGH match within tolerance, after the match evidence is
 * recorded: the bill then carries the invoice's number, date and billed total (AW46), so AW45 is met by construction.
 * It is eligible when the automatic limit is above 0 and the absolute total is at most min(automatic limit, clerk
 * limit); above that the bill stays {@code AWAITING_APPROVAL}, submitted by {@code SYSTEM}, as S12 left it. MEDIUM,
 * ambiguous, discrepancy and {@code MATCH_EXCEPTION} matches, and EDI bills, never reach here.
 *
 * <p><b>No person's input.</b> The bill posts with the class of its lines (stocked lines {@code RECEIPT_MATCHED}),
 * no classification, no difference and no override. Whatever would need one, and every refusal of the posting, skips
 * the approval: the bill stays {@code AWAITING_APPROVAL} with no approval field and one {@value #AUDIT_SKIPPED} row
 * carries the code ({@code AP_BILL_ZERO_TOTAL}, {@code AP_BILL_UNCLASSIFIED}, {@code AP_BILL_TOTALS_UNRECONCILED},
 * {@code PERIOD_CLOSED}, {@code PERIOD_HARD_LOCKED}, {@code GL_MAPPING_NOT_CONFIGURED}; S43 adds the tax-on-resale
 * hold). The match itself is kept.
 *
 * <p><b>The savepoint.</b> {@link VendorBillPostingService#post} is {@code MANDATORY}: a refusal crossing it would
 * mark the match transaction rollback-only. The JPA dialect in use offers no savepoints ({@code PROPAGATION_NESTED}),
 * so this is the ruling's "equivalent pre-check": every refusal the posting can answer is asked first, writing
 * nothing — the entry's legs in memory, then the posting date's period and each leg's mapping in a transaction of
 * its own, which a refusal rolls back alone. Only a pre-check that passes is followed by the posting. A period closed
 * or a mapping removed between the two (a race of milliseconds) makes the posting refuse: its exception is never
 * caught here, so the whole {@code /match} rolls back and answers the posting's 422 ({@code PERIOD_CLOSED}, {@code
 * PERIOD_HARD_LOCKED}, {@code GL_MAPPING_NOT_CONFIGURED}). Nothing retries it: the caller must resend the invoice, and
 * a resend's pre-check then sees the refusal and ends matched, {@code AWAITING_APPROVAL}, with the skip row (ruling
 * 6063520413 item 3). The bill is never left half-approved.
 */
@Slf4j
@Component
public class VendorBillAutoApproval {

    static final String AUDIT_APPROVE = "VENDOR_BILL_AUTO_APPROVE";
    static final String AUDIT_SKIPPED = "VENDOR_BILL_AUTO_APPROVE_SKIPPED";

    /** The lowest score of a HIGH match (P3), named in the approval's justification. */
    static final int STRONG_SCORE = 70;

    private static final String SYSTEM = VendorBillDecisions.SYSTEM;
    private static final VendorBillPostingService.Classification NO_CLASSIFICATION =
            new VendorBillPostingService.Classification(null, null);

    private final Clock clock;
    private final ApApprovalPolicy policy;
    private final VendorBillPostingService postingService;
    private final AccountingPeriodGate periodGate;
    private final VendorBillRepository bills;
    private final VendorBillLineRepository billLines;
    private final AccountingAuditLogRepository auditLogs;
    private final LedgerCurrency ledgerCurrency;
    private final TransactionTemplate precheckTransaction;

    public VendorBillAutoApproval(
            Clock clock,
            ApApprovalPolicy policy,
            VendorBillPostingService postingService,
            AccountingPeriodGate periodGate,
            VendorBillRepository bills,
            VendorBillLineRepository billLines,
            AccountingAuditLogRepository auditLogs,
            LedgerCurrency ledgerCurrency,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.policy = policy;
        this.postingService = postingService;
        this.periodGate = periodGate;
        this.bills = bills;
        this.billLines = billLines;
        this.auditLogs = auditLogs;
        this.ledgerCurrency = ledgerCurrency;
        this.precheckTransaction = new TransactionTemplate(transactionManager);
        this.precheckTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Approves {@code bill}, just routed {@code AWAITING_APPROVAL} by a HIGH match, when it is eligible and nothing
     * would refuse its posting; otherwise leaves it as it is (writing a {@value #AUDIT_SKIPPED} row when a guard
     * refused it). Joins the match transaction.
     *
     * @param evidence the match evidence just recorded
     * @param score the match score
     * @return whether the bill was approved
     */
    public boolean approveIfEligible(@NonNull VendorBill bill, @NonNull VendorBillMatchEvidence evidence, int score) {
        ApApprovalPolicy.Settings settings = policy.forDecision();
        if (!settings.automaticallyApprovable(bill.getTotalAmount())) {
            return false;
        }
        VendorBillApprovalServiceImpl.Decision decision =
                new VendorBillApprovalServiceImpl.Decision(settings.tier(bill.getTotalAmount()), settings, null);
        BigDecimal limit = settings.automaticLimitApplied();
        Optional<RuntimeException> refusal = refusal(bill);
        if (refusal.isPresent()) {
            String code = VendorBillApprovalServiceImpl.codeOf(refusal.get());
            audit(
                    bill,
                    AUDIT_SKIPPED,
                    null,
                    evidence,
                    decision,
                    "code=" + code + ";limitApplied=" + limit.toPlainString() + ";message="
                            + refusal.get().getMessage());
            log.info(
                    "Automatic approval of vendor bill {} skipped | billId={} | score={} | limit={} | code={}",
                    bill.getBillNumber(),
                    bill.getVendorBillId(),
                    score,
                    limit.toPlainString(),
                    code);
            return false;
        }
        // Never caught (ruling 6063520413 item 3): a refusal here, in the pre-check's race, rolls the whole /match
        // back;
        // the caller must resend, and the resend's pre-check ends in the skipped state.
        VendorBillGlPosting posting = postingService.post(bill, NO_CLASSIFICATION, null, SYSTEM);
        String justification = "Approved automatically: match score " + score + " (strong >= " + STRONG_SCORE
                + "), total " + bill.getTotalAmount().toPlainString() + " <= automatic limit " + limit.toPlainString();
        bill.setStatus(VendorBillStatus.APPROVED);
        bill.setRejectionReason(null);
        bill.setApprovedBy(SYSTEM);
        bill.setApprovedByKind(VendorBillApproverKind.SYSTEM);
        bill.setApprovedAt(Instant.now(clock));
        bill.setApprovalJustification(justification);
        bill.setModifiedBy(SYSTEM);
        bills.save(bill);
        audit(
                bill,
                AUDIT_APPROVE,
                justification,
                evidence,
                decision,
                "limitApplied=" + limit.toPlainString() + ";journalEntryId=" + posting.getJournalEntryId()
                        + ";postingDate=" + posting.getPostingDate() + ";postingDateRule="
                        + posting.getPostingDateRule() + ";roundingAdjustment="
                        + posting.getRoundingAdjustment().toPlainString());
        log.info(
                "Vendor bill {} approved automatically | billId={} | score={} | limit={} | entry={}",
                bill.getBillNumber(),
                bill.getVendorBillId(),
                score,
                limit.toPlainString(),
                posting.getJournalEntryId());
        return true;
    }

    /**
     * The refusal the posting would answer, asked without writing anything (guard steps 5 and 6 of #2510): the
     * currency, the entry's legs with the lines' own classes (a 0.00 total, a non-stock line without a key, the lines
     * apart from the billed total), then, in a transaction of its own, the posting date's period and each leg's
     * mapping. Empty when the posting would go through.
     */
    Optional<RuntimeException> refusal(@NonNull VendorBill bill) {
        if (ledgerCurrency.isForeign(bill.getCurrency())) {
            return Optional.of(new VendorBillException(
                    VendorBillException.Code.AP_BILL_NOT_APPROVABLE,
                    "Bill " + bill.getBillNumber() + " is not in the ledger currency " + ledgerCurrency.code()));
        }
        List<VendorBillLine> lines = billLines.findByVendorBill_VendorBillIdOrderByLineNumber(bill.getVendorBillId());
        List<VendorBillPostingService.Leg> legs;
        try {
            legs = VendorBillPostingService.legs(
                    bill, lines, NO_CLASSIFICATION, VendorBillPostingService.difference(bill));
        } catch (VendorBillException refused) {
            return Optional.of(refused);
        }
        try {
            precheckTransaction.executeWithoutResult(_ -> {
                LocalDate date = postingService.postingDate(bill).date();
                if (periodGate.isHardLocked(date)) {
                    throw new Skip("PERIOD_HARD_LOCKED", "The posting date " + date + " is before the hard lock");
                }
                if (periodGate.isPostingBlocked(date)) {
                    throw new Skip("PERIOD_CLOSED", "The posting date " + date + " falls in a CLOSED period");
                }
                postingService.requireMapped(bill, legs, date);
                // Nothing was written; end the transaction without a commit.
                throw new Skip(null, "pre-check passed");
            });
        } catch (Skip skip) {
            return skip.code == null ? Optional.empty() : Optional.of(skip);
        } catch (RuntimeException refused) {
            return Optional.of(refused);
        }
        return Optional.empty();
    }

    private void audit(
            VendorBill bill,
            String operation,
            @Nullable String justification,
            VendorBillMatchEvidence evidence,
            VendorBillApprovalServiceImpl.Decision decision,
            String details) {
        String currency = bill.getCurrency() == null || bill.getCurrency().isBlank()
                ? ledgerCurrency.code()
                : bill.getCurrency().trim();
        AccountingAuditLog row = new AccountingAuditLog();
        row.setEntityType(VendorBillApprovalServiceImpl.AUDIT_ENTITY_TYPE);
        row.setEntityId(bill.getVendorBillId());
        row.setOperation(operation);
        row.setUserId(SYSTEM);
        row.setJustification(justification);
        String text = VendorBillApprovalServiceImpl.auditText(bill, evidence, currency, decision, details);
        row.setNewValue(text.length() <= 2000 ? text : text.substring(0, 2000));
        auditLogs.save(row);
    }

    /**
     * The outcome of the pre-check transaction, thrown so the transaction always rolls back: a refusal code, or none
     * when the pre-check passed.
     */
    static final class Skip extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        private final @Nullable String code;

        Skip(@Nullable String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }

        @Nullable
        String code() {
            return code;
        }
    }
}
