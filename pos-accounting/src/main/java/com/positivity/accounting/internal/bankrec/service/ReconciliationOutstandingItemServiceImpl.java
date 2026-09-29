package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.dto.OutstandingItemJustificationRequest;
import com.positivity.accounting.internal.bankrec.dto.OutstandingItemReasonRequest;
import com.positivity.accounting.internal.bankrec.dto.OutstandingItemRegisterRequest;
import com.positivity.accounting.internal.bankrec.dto.OutstandingItemResponse;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemKind;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemSide;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.intake.Justification;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationBankMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.exception.ReconciliationLineIneligibleException;
import com.positivity.accounting.internal.exception.ReconciliationNotFoundException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outstanding (timing) items (SPEC-manual-bank-reconciliation §3.6, §5.4; story S4, #2303). An item explains a
 * difference and never posts (O3, C2). A line or bank transaction is in at most one {@code OPEN} item and
 * never also in an active match (O1): the rows are read under a row lock, and the partial uniques on
 * {@code OPEN} items are the backstop.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ReconciliationOutstandingItemServiceImpl implements ReconciliationOutstandingItemService {

    private static final String JUSTIFICATION = "justification";

    private final ReconciliationSupport support;
    private final ReconciliationEligibility eligibility;
    private final ReconciliationCalculator calculator;
    private final BankReconciliationOutstandingItemRepository items;
    private final BankReconciliationGlMatchRepository glMatches;
    private final BankReconciliationBankMatchRepository bankMatches;
    private final BankReconciliationRepository reconciliations;
    private final BankStatementRepository statements;
    private final BankRecAuditRecorder audit;
    private final BankRecSettings settings;

    @Override
    public @NonNull OutstandingItemResponse register(
            @NonNull UUID reconciliationId, @NonNull OutstandingItemRegisterRequest request) {
        BankReconciliation recon = support.requireOpen(reconciliationId);
        if ((request.getGlLineId() == null) == (request.getBankTransactionId() == null)) {
            throw new BankRecException(
                    BankRecErrorCode.VALIDATION_ERROR, "Name exactly one of glLineId and bankTransactionId");
        }
        OutstandingItemKind kind = request.getItemKind();
        OutstandingItemSide side =
                kind == OutstandingItemKind.BANK_ERROR_PENDING ? OutstandingItemSide.BANK : OutstandingItemSide.LEDGER;
        BankReconciliationOutstandingItem item = new BankReconciliationOutstandingItem();
        item.setGlAccountId(recon.getGlAccountId());
        item.setSide(side);
        item.setItemKind(kind);
        if (side == OutstandingItemSide.LEDGER) {
            if (request.getGlLineId() == null) {
                throw notEligible(kind + " is a ledger-side item; name the glLineId");
            }
            JournalEntryLine line = requireLedgerLine(recon, request.getGlLineId(), kind);
            item.setGlLineId(line.getLineId());
            item.setSignedAmount(line.getDebitAmount().subtract(line.getCreditAmount()));
            item.setItemDate(line.getJournalEntry().getTransactionDate().toLocalDate());
        } else {
            if (request.getBankTransactionId() == null) {
                throw notEligible("BANK_ERROR_PENDING is a bank-side item; name the bankTransactionId");
            }
            BankTransaction row = requireBankRow(recon, request.getBankTransactionId());
            item.setBankTransactionId(row.getBankTransactionId());
            item.setSignedAmount(row.getSignedAmount());
            item.setItemDate(row.getTransactionDate());
        }
        String justification = justificationFor(kind, item.getItemDate(), request.getJustification());

        String actor = support.currentUser();
        item.setJustification(justification);
        item.setStatus(OutstandingItemStatus.OPEN);
        item.setRegisteredInReconciliationId(reconciliationId);
        item.setRegisteredBy(actor);
        item.setRegisteredAt(support.now());
        BankReconciliationOutstandingItem saved;
        try {
            saved = items.saveAndFlush(item);
        } catch (DataIntegrityViolationException e) {
            throw new ReconciliationLineIneligibleException(
                    "The line was concurrently registered in another OPEN item");
        }
        audit.record(
                BankRecAuditRecorder.OUTSTANDING_ITEM,
                saved.getOutstandingItemId(),
                BankRecAuditRecorder.RECONCILIATION_OUTSTANDING_REGISTER,
                actor,
                justification,
                null,
                OutstandingItemStatus.OPEN + ";kind=" + kind + ";reconciliationId=" + reconciliationId);
        support.refresh(recon);
        log.info("Registered {} item {} in reconciliation {}", kind, saved.getOutstandingItemId(), reconciliationId);
        return OutstandingItemResponse.from(saved, recon.getStatementEndDate());
    }

    @Override
    public @NonNull OutstandingItemResponse release(
            @NonNull UUID reconciliationId, @NonNull UUID itemId, @NonNull OutstandingItemReasonRequest request) {
        BankReconciliation recon = support.requireOpen(reconciliationId);
        String reason = Justification.required(request.getReason(), "reason");
        BankReconciliationOutstandingItem item = requireItem(recon, itemId);
        if (item.getStatus() != OutstandingItemStatus.OPEN) {
            throw notEligible("Item " + itemId + " is " + item.getStatus() + "; only an OPEN item is released");
        }
        BankReconciliation registering = reconciliations
                .findById(item.getRegisteredInReconciliationId())
                .orElseThrow(() -> notEligible("Item " + itemId + " has no registering reconciliation"));
        if (registering.getStatus() == ReconciliationStatus.FINALIZED) {
            throw notEligible("Item " + itemId + " was registered in FINALIZED reconciliation "
                    + registering.getReconciliationId() + "; it can no longer be released");
        }
        String actor = support.currentUser();
        item.setStatus(OutstandingItemStatus.RELEASED);
        item.setReleasedAt(support.now());
        item.setReleasedBy(actor);
        item.setReleaseReason(reason);
        items.save(item);
        audit.record(
                BankRecAuditRecorder.OUTSTANDING_ITEM,
                itemId,
                BankRecAuditRecorder.RECONCILIATION_OUTSTANDING_RELEASE,
                actor,
                reason,
                OutstandingItemStatus.OPEN.name(),
                OutstandingItemStatus.RELEASED.name());
        support.refresh(recon);
        return OutstandingItemResponse.from(item, recon.getStatementEndDate());
    }

    @Override
    public @NonNull OutstandingItemResponse reaffirm(
            @NonNull UUID reconciliationId,
            @NonNull UUID itemId,
            @NonNull OutstandingItemJustificationRequest request) {
        BankReconciliation recon = support.requireOpen(reconciliationId);
        String justification = Justification.requiredByRule(request.getJustification(), JUSTIFICATION);
        BankReconciliationOutstandingItem item = requireItem(recon, itemId);
        if (item.getStatus() != OutstandingItemStatus.OPEN
                || item.getItemKind() != OutstandingItemKind.OTHER_LEDGER_TIMING
                || !calculator.isAged(item, recon.getStatementEndDate())) {
            throw notEligible("Only an OPEN OTHER_LEDGER_TIMING item dated more than " + settings.agingWarningDays()
                    + " days before the window end is reaffirmed; item " + itemId + " is " + item.getItemKind()
                    + " " + item.getStatus() + " dated " + item.getItemDate());
        }
        String actor = support.currentUser();
        item.setLastReaffirmedInReconciliationId(reconciliationId);
        item.setLastReaffirmedBy(actor);
        item.setLastReaffirmedAt(support.now());
        item.setReaffirmJustification(justification);
        items.save(item);
        audit.record(
                BankRecAuditRecorder.OUTSTANDING_ITEM,
                itemId,
                BankRecAuditRecorder.RECONCILIATION_OUTSTANDING_REAFFIRM,
                actor,
                justification,
                null,
                "reconciliationId=" + reconciliationId);
        support.refresh(recon);
        return OutstandingItemResponse.from(item, recon.getStatementEndDate());
    }

    @Override
    public @NonNull OutstandingItemResponse clearInGap(
            @NonNull UUID reconciliationId,
            @NonNull UUID itemId,
            @NonNull OutstandingItemJustificationRequest request) {
        BankReconciliation recon = support.requireOpen(reconciliationId);
        String justification = Justification.requiredByRule(request.getJustification(), JUSTIFICATION);
        BankStatement statement = recon.getStatementId() == null
                ? null
                : statements.findById(recon.getStatementId()).orElse(null);
        if (statement == null || statement.getGapAcknowledgement() == null) {
            throw notEligible("Clear-in-gap is only for a reconciliation of a statement with a gap acknowledgement");
        }
        BankReconciliationOutstandingItem item = requireItem(recon, itemId);
        if (item.getStatus() != OutstandingItemStatus.OPEN
                || !item.getItemDate().isBefore(statement.getStartDate())
                || reconciliationId.equals(item.getRegisteredInReconciliationId())) {
            throw notEligible("Item " + itemId + " must be OPEN, dated before " + statement.getStartDate()
                    + " and registered in an earlier reconciliation");
        }
        String actor = support.currentUser();
        item.setStatus(OutstandingItemStatus.CLEARED_IN_GAP);
        item.setClosedOn(statement.getStartDate().minusDays(1));
        item.setClearedInReconciliationId(reconciliationId);
        item.setClearedAt(support.now());
        item.setClearedBy(actor);
        item.setClearanceJustification(justification);
        items.save(item);
        audit.record(
                BankRecAuditRecorder.OUTSTANDING_ITEM,
                itemId,
                BankRecAuditRecorder.RECONCILIATION_OUTSTANDING_CLEAR_IN_GAP,
                actor,
                justification,
                OutstandingItemStatus.OPEN.name(),
                OutstandingItemStatus.CLEARED_IN_GAP.name());
        support.refresh(recon);
        return OutstandingItemResponse.from(item, recon.getStatementEndDate());
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** A POSTED line on the account, dated on or before the window end, in no active match or OPEN item, signed for its kind. */
    private JournalEntryLine requireLedgerLine(BankReconciliation recon, UUID glLineId, OutstandingItemKind kind) {
        JournalEntryLine line = eligibility.lockLedger(recon, List.of(glLineId)).get(0);
        if (line.getJournalEntry() == null || line.getJournalEntry().getStatus() != JournalEntryStatus.POSTED) {
            throw notEligible("GL line " + glLineId + " is not on a POSTED entry");
        }
        if (line.getJournalEntry().getTransactionDate().toLocalDate().isAfter(recon.getStatementEndDate())) {
            throw notEligible("GL line " + glLineId + " is dated after the window end " + recon.getStatementEndDate());
        }
        if (!glMatches.findByGlLineIdInAndActiveTrue(List.of(glLineId)).isEmpty()) {
            throw notEligible("GL line " + glLineId + " is in an active match");
        }
        if (!items.findByGlLineIdInAndStatus(List.of(glLineId), OutstandingItemStatus.OPEN)
                .isEmpty()) {
            throw notEligible("GL line " + glLineId + " is already in an OPEN outstanding item");
        }
        BigDecimal signed = line.getDebitAmount().subtract(line.getCreditAmount());
        if ((kind == OutstandingItemKind.DEPOSIT_IN_TRANSIT && signed.signum() <= 0)
                || (kind == OutstandingItemKind.OUTSTANDING_CHECK && signed.signum() >= 0)) {
            throw notEligible(kind + " needs a " + (kind == OutstandingItemKind.DEPOSIT_IN_TRANSIT ? "debit" : "credit")
                    + " to cash; GL line " + glLineId + " is " + signed);
        }
        return line;
    }

    /** An UNMATCHED bank row on the account, dated on or before the window end, in no match or OPEN item. */
    private BankTransaction requireBankRow(BankReconciliation recon, UUID bankTransactionId) {
        BankTransaction row =
                eligibility.lockBank(recon, List.of(bankTransactionId)).get(0);
        if (row.getStatus() != BankTransactionStatus.UNMATCHED) {
            throw notEligible("Bank transaction " + bankTransactionId + " is " + row.getStatus() + ", not UNMATCHED");
        }
        if (row.getTransactionDate().isAfter(recon.getStatementEndDate())) {
            throw notEligible("Bank transaction " + bankTransactionId + " is dated after the window end");
        }
        if (!bankMatches
                .findByBankTransactionIdInAndActiveTrue(List.of(bankTransactionId))
                .isEmpty()) {
            throw notEligible("Bank transaction " + bankTransactionId + " is in an active match");
        }
        if (!eligibility.openItemBankIds(List.of(bankTransactionId)).isEmpty()) {
            throw notEligible("Bank transaction " + bankTransactionId + " is already in an OPEN outstanding item");
        }
        return row;
    }

    /**
     * §3.6: a justification is required for {@code OTHER_LEDGER_TIMING}, {@code BANK_ERROR_PENDING} and any item
     * older than the aging days at registration; otherwise it is optional.
     */
    private @Nullable String justificationFor(OutstandingItemKind kind, LocalDate itemDate, @Nullable String value) {
        boolean aged = itemDate.isBefore(support.today().minusDays(settings.agingWarningDays()));
        if (kind == OutstandingItemKind.OTHER_LEDGER_TIMING || kind == OutstandingItemKind.BANK_ERROR_PENDING || aged) {
            return Justification.requiredByRule(value, JUSTIFICATION);
        }
        return Justification.optional(value, JUSTIFICATION);
    }

    private BankReconciliationOutstandingItem requireItem(BankReconciliation recon, UUID itemId) {
        return items.findByOutstandingItemIdAndGlAccountId(itemId, recon.getGlAccountId())
                .orElseThrow(() -> new ReconciliationNotFoundException(
                        "Outstanding item " + itemId + " not found on account " + recon.getAccountCode()));
    }

    private static BankRecException notEligible(String message) {
        return new BankRecException(BankRecErrorCode.OUTSTANDING_ITEM_NOT_ELIGIBLE, message);
    }
}
