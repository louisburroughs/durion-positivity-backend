package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.WorkItem;
import com.positivity.accounting.internal.dto.WorkItemAction;
import com.positivity.accounting.internal.dto.WorkItemPostingLine;
import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillMatchCandidate;
import com.positivity.accounting.internal.enums.VendorBillAction;
import com.positivity.accounting.internal.enums.VendorBillApproverKind;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.enums.WorkItemType;
import com.positivity.accounting.internal.repository.VendorBillMatchCandidateRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.OpenVendorBills.OpenBill;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The BILLS work items (CAP:550 S19, #2515; SPEC-accounting-workspace §7.1 "Work items"): bills to check, pick a match
 * for, send, approve and pay. Each type is shown to the holders of the permission that lets them act on it; the actions
 * are {@link VendorBillReader#availableActions}, the rules the bill's own read applies, so a rule that blocks the caller
 * shows the action disabled with its code (P5). {@code CURRENCY_HOLD}, {@code REJECTED}, {@code VOIDED} and {@code PAID}
 * bills produce nothing.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BillWorkItemSource {

    /** Statuses of the bills that need a person before they are approved. */
    static final Set<VendorBillStatus> REVIEW =
            EnumSet.of(
                    VendorBillStatus.MATCH_EXCEPTION,
                    VendorBillStatus.PENDING_RECEIPT_MATCH,
                    VendorBillStatus.AWAITING_APPROVAL);

    /** {@code blockedReason} of {@code PAY} on a bill its payer approved. */
    static final String BLOCKED_SELF_APPROVED = "AP_PAYMENT_SELF_APPROVED_BILL";

    private static final String PAY = "PAY";

    private final VendorBillRepository vendorBills;
    private final VendorBillMatchCandidateRepository candidates;
    private final VendorBillReader reader;
    private final ApApprovalPolicy policy;
    private final SupplierVendorCopies vendorCopies;
    private final OpenVendorBills openBills;
    private final BillApprovalPreview approvalPreview;
    private final AccountingCalendarZoneResolver zoneResolver;

    /** The caller's bills items on {@code today}, unordered. */
    public @NonNull List<WorkItem> items(@NonNull LocalDate today) {
        List<WorkItem> items = new ArrayList<>();
        if (VendorBillDecisions.mayTake(VendorBillAction.CORRECT_EXCEPTION)) {
            items.addAll(reviewItems());
        }
        if (WorkItems.has(AccountingPermissions.AP_PAY)) {
            items.addAll(dueSoon(today));
        }
        return items;
    }

    private List<WorkItem> reviewItems() {
        List<VendorBill> bills = vendorBills.findByStatusIn(REVIEW);
        if (bills.isEmpty()) {
            return List.of();
        }
        Set<UUID> withCandidates = new HashSet<>();
        for (VendorBillMatchCandidate candidate : candidates.findByVendorBill_VendorBillIdInAndResolvedFalse(
                bills.stream().map(VendorBill::getVendorBillId).toList())) {
            withCandidates.add(candidate.getVendorBill().getVendorBillId());
        }
        ApApprovalPolicy.Settings settings = policy.settings();
        String caller = VendorBillDecisions.callerOrNull();
        List<WorkItem> items = new ArrayList<>();
        for (VendorBill bill : bills) {
            boolean hasCandidates = withCandidates.contains(bill.getVendorBillId());
            WorkItem item =
                    switch (bill.getStatus()) {
                        case MATCH_EXCEPTION, PENDING_RECEIPT_MATCH ->
                            matchItem(bill, hasCandidates, settings, caller);
                        case AWAITING_APPROVAL -> awaitingApproval(bill, settings, caller);
                        default -> null;
                    };
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    private @Nullable WorkItem matchItem(
            VendorBill bill, boolean hasCandidates, ApApprovalPolicy.Settings settings, @Nullable String caller) {
        VendorBillReview.Channel channel = VendorBillReader.channelOf(bill);
        boolean awaitsInvoice = VendorBillReader.awaitsInvoice(
                channel, channel == VendorBillReview.Channel.GOODS_RECEIPT && reader.invoiceMatched(bill));
        boolean exception = bill.getStatus() == VendorBillStatus.MATCH_EXCEPTION;
        WorkItemType type;
        String reason;
        Set<VendorBillAction> shown;
        if (hasCandidates) {
            type = WorkItemType.BILL_PICK_MATCH;
            reason = VendorBillReader.REASON_PICK_A_MATCH;
            shown = EnumSet.of(VendorBillAction.SELECT_CANDIDATE);
            if (!VendorBillDecisions.mayTake(VendorBillAction.SELECT_CANDIDATE)) {
                return null;
            }
        } else if (exception) {
            type = WorkItemType.BILL_MATCH_EXCEPTION;
            reason = bill.getStatus().name();
            shown = EnumSet.allOf(VendorBillAction.class);
            if (!VendorBillDecisions.mayTake(VendorBillAction.CORRECT_EXCEPTION)) {
                return null;
            }
        } else {
            type = WorkItemType.BILL_WAITING_ON_DELIVERY;
            reason = awaitsInvoice
                    ? VendorBillReader.REASON_INVOICE_NOT_MATCHED
                    : VendorBillReader.REASON_NO_DELIVERY_RECORDED;
            shown = EnumSet.of(VendorBillAction.SUBMIT_FOR_APPROVAL);
            if (!VendorBillDecisions.mayTake(VendorBillAction.SUBMIT_FOR_APPROVAL)) {
                return null;
            }
        }
        boolean creatorFirst =
                exception && caller != null && vendorCopies.isCreatorsFirstBill(bill.getVendorId(), caller);
        List<WorkItemAction> actions = actions(
                VendorBillReader.availableActions(
                        bill.getStatus(),
                        channel,
                        hasCandidates,
                        awaitsInvoice,
                        false,
                        false,
                        VendorBillReader.blocks(bill, settings, creatorFirst)),
                shown);
        return WorkItems.item(
                type,
                bill.getVendorBillId().toString(),
                billReferences(bill),
                reason,
                bill.getTotalAmount(),
                bill.getStatus().name(),
                reviewDate(bill),
                actions,
                List.of(),
                WorkItems.routing("vendorBillId", bill.getVendorBillId(), "vendorId", bill.getVendorId()));
    }

    private @Nullable WorkItem awaitingApproval(
            VendorBill bill, ApApprovalPolicy.Settings settings, @Nullable String caller) {
        VendorBillReview.RequiredTier tier = settings.tier(bill.getTotalAmount());
        if (!VendorBillDecisions.mayTake(VendorBillAction.APPROVE) || !VendorBillDecisions.mayDecideTier(tier)) {
            return null;
        }
        VendorBillReview.Channel channel = VendorBillReader.channelOf(bill);
        boolean awaitsInvoice = VendorBillReader.awaitsInvoice(
                channel, channel == VendorBillReview.Channel.GOODS_RECEIPT && reader.invoiceMatched(bill));
        boolean creatorFirst = caller != null && vendorCopies.isCreatorsFirstBill(bill.getVendorId(), caller);
        List<WorkItemAction> actions = actions(
                VendorBillReader.availableActions(
                        bill.getStatus(),
                        channel,
                        false,
                        awaitsInvoice,
                        false,
                        false,
                        VendorBillReader.blocks(bill, settings, creatorFirst)),
                EnumSet.of(VendorBillAction.APPROVE, VendorBillAction.REJECT));
        boolean canApprove = actions.stream().anyMatch(action -> VendorBillAction.APPROVE.name().equals(action.action()));
        List<WorkItemPostingLine> preview = canApprove ? approvalPreview.preview(bill) : List.of();
        return WorkItems.item(
                WorkItemType.BILL_AWAITING_APPROVAL,
                bill.getVendorBillId().toString(),
                billReferences(bill),
                tier.name(),
                bill.getTotalAmount(),
                bill.getStatus().name(),
                reviewDate(bill),
                actions,
                preview,
                WorkItems.routing("vendorBillId", bill.getVendorBillId(), "vendorId", bill.getVendorId()));
    }

    private List<WorkItem> dueSoon(LocalDate today) {
        LocalDate horizon = today.plusDays(CashOutlookServiceImpl.BILLS_SOON_DAYS);
        List<OpenBill> due = openBills.load().approved().stream()
                .filter(open -> open.openAmount().signum() > 0)
                .filter(open -> open.dueDate() != null && !open.dueDate().isAfter(horizon))
                .toList();
        if (due.isEmpty()) {
            return List.of();
        }
        ApApprovalPolicy.Settings settings = policy.settings();
        String caller = VendorBillDecisions.callerOrNull();
        List<WorkItem> items = new ArrayList<>();
        for (OpenBill open : due) {
            VendorBill bill = open.bill();
            LocalDate dueDate = open.dueDate();
            boolean selfApproved = caller != null
                    && bill.getApprovedByKind() == VendorBillApproverKind.PERSON
                    && caller.equals(bill.getApprovedBy())
                    && !settings.allowApproverPayment();
            items.add(WorkItems.item(
                    WorkItemType.BILLS_DUE_SOON,
                    bill.getVendorBillId().toString(),
                    WorkItems.references(
                            "vendor", bill.getVendorName(),
                            "billNumber", bill.getBillNumber(),
                            "purchaseOrderNumber", bill.getPurchaseOrderNumber(),
                            "dueDate", dueDate.toString(),
                            "estimated", Boolean.toString(open.estimated()),
                            "termsSource", open.estimate() == null ? null : open.estimate().termsSource().name()),
                    dueDate.isBefore(today) ? "OVERDUE" : "DUE_SOON",
                    open.openAmount(),
                    bill.getStatus().name(),
                    dueDate,
                    List.of(selfApproved ? WorkItemAction.blocked(PAY, BLOCKED_SELF_APPROVED) : WorkItemAction.allowed(PAY)),
                    List.of(),
                    WorkItems.routing("vendorBillId", bill.getVendorBillId(), "vendorId", bill.getVendorId())));
        }
        return items;
    }

    private static List<WorkItemAction> actions(
            List<VendorBillReview.AvailableAction> available, Set<VendorBillAction> shown) {
        return available.stream()
                .filter(action -> shown.contains(action.action()))
                .map(action -> new WorkItemAction(action.action().name(), action.allowed(), action.blockedReason()))
                .toList();
    }

    private static java.util.Map<String, String> billReferences(VendorBill bill) {
        return WorkItems.references(
                "vendor", bill.getVendorName(),
                "billNumber", bill.getBillNumber(),
                "purchaseOrderNumber", bill.getPurchaseOrderNumber(),
                "billDate", bill.getBillDate() == null ? null : bill.getBillDate().toLocalDate().toString());
    }

    /** When the bill started waiting: its submission for an approval, else its arrival; its bill date as a last resort. */
    private LocalDate reviewDate(VendorBill bill) {
        Instant at = bill.getStatus() == VendorBillStatus.AWAITING_APPROVAL && bill.getSubmittedAt() != null
                ? bill.getSubmittedAt()
                : bill.getCreatedAt();
        if (at != null) {
            return zoneResolver.postingDate(at);
        }
        return bill.getBillDate() == null ? zoneResolver.today() : bill.getBillDate().toLocalDate();
    }
}
