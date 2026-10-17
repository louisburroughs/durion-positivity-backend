package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.dto.WorkItem;
import com.positivity.accounting.internal.dto.WorkItemAction;
import com.positivity.accounting.internal.dto.WorkItemPostingLine;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillLine;
import com.positivity.accounting.internal.entity.VendorBillMatchCandidate;
import com.positivity.accounting.internal.entity.VendorBillMatchEvidence;
import com.positivity.accounting.internal.enums.VendorBillAction;
import com.positivity.accounting.internal.enums.VendorBillApproverKind;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.enums.WorkItemType;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import com.positivity.accounting.internal.repository.VendorBillMatchCandidateRepository;
import com.positivity.accounting.internal.repository.VendorBillMatchEvidenceRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.accounting.internal.service.OpenVendorBills.OpenBill;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 *
 * <p>Two passes keep a read cheap. {@link #items} decides which bills are items, from the bills alone and one batched
 * read of the open candidates; the count per type needs only that. {@link #complete} then fills, for the one page the
 * caller gets, what costs more: the actions (the match evidence and lines of the page's goods-receipt bills in one
 * read each, and the vendors the caller created in one more) and the approval's posting preview.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BillWorkItemSource {

    /** Statuses of the bills that need a person before they are approved. */
    static final Set<VendorBillStatus> REVIEW = EnumSet.of(
            VendorBillStatus.MATCH_EXCEPTION,
            VendorBillStatus.PENDING_RECEIPT_MATCH,
            VendorBillStatus.AWAITING_APPROVAL);

    /** The types whose actions {@link #complete} fills. */
    static final Set<WorkItemType> REVIEW_TYPES = EnumSet.of(
            WorkItemType.BILL_MATCH_EXCEPTION,
            WorkItemType.BILL_PICK_MATCH,
            WorkItemType.BILL_WAITING_ON_DELIVERY,
            WorkItemType.BILL_AWAITING_APPROVAL);

    /** {@code blockedReason} of {@code PAY} on a bill its payer approved. */
    static final String BLOCKED_SELF_APPROVED = "AP_PAYMENT_SELF_APPROVED_BILL";

    /** {@code blockedReason} of {@code PAY} on a bill of a vendor on AP hold. */
    static final String BLOCKED_AP_HOLD = "VENDOR_ON_AP_HOLD";

    private static final String PAY = "PAY";
    private static final String ROUTING_BILL = "vendorBillId";

    private final VendorBillRepository vendorBills;
    private final VendorBillMatchCandidateRepository candidates;
    private final VendorBillMatchEvidenceRepository evidence;
    private final VendorBillLineRepository billLines;
    private final ApApprovalPolicy policy;
    private final SupplierVendorCopies vendorCopies;
    private final OpenVendorBills openBills;
    private final BillApprovalPreview approvalPreview;
    private final AccountingCalendarZoneResolver zoneResolver;

    /** The caller's bills items on {@code today}, unordered, their actions and previews still to be completed. */
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
        List<WorkItem> items = new ArrayList<>();
        for (VendorBill bill : bills) {
            WorkItemType type = typeOf(bill, withCandidates.contains(bill.getVendorBillId()), settings);
            if (type != null) {
                items.add(WorkItems.item(
                        type,
                        bill.getVendorBillId().toString(),
                        billReferences(bill),
                        reasonOf(type, bill, settings),
                        bill.getTotalAmount(),
                        bill.getStatus().name(),
                        reviewDate(bill),
                        List.of(),
                        List.of(),
                        WorkItems.routing(ROUTING_BILL, bill.getVendorBillId(), "vendorId", bill.getVendorId())));
            }
        }
        return items;
    }

    /** The item type of a review bill for this caller; null when the caller may not act on it. */
    private static @Nullable WorkItemType typeOf(
            VendorBill bill, boolean hasCandidates, ApApprovalPolicy.Settings settings) {
        if (bill.getStatus() == VendorBillStatus.AWAITING_APPROVAL) {
            boolean mayApprove = VendorBillDecisions.mayTake(VendorBillAction.APPROVE)
                    && VendorBillDecisions.mayDecideTier(settings.tier(bill.getTotalAmount()));
            return mayApprove ? WorkItemType.BILL_AWAITING_APPROVAL : null;
        }
        if (hasCandidates) {
            return VendorBillDecisions.mayTake(VendorBillAction.SELECT_CANDIDATE) ? WorkItemType.BILL_PICK_MATCH : null;
        }
        if (bill.getStatus() == VendorBillStatus.MATCH_EXCEPTION) {
            return VendorBillDecisions.mayTake(VendorBillAction.CORRECT_EXCEPTION)
                    ? WorkItemType.BILL_MATCH_EXCEPTION
                    : null;
        }
        return VendorBillDecisions.mayTake(VendorBillAction.SUBMIT_FOR_APPROVAL)
                ? WorkItemType.BILL_WAITING_ON_DELIVERY
                : null;
    }

    private static String reasonOf(WorkItemType type, VendorBill bill, ApApprovalPolicy.Settings settings) {
        return switch (type) {
            case BILL_PICK_MATCH -> VendorBillReader.REASON_PICK_A_MATCH;
            case BILL_MATCH_EXCEPTION -> bill.getStatus().name();
            case BILL_AWAITING_APPROVAL -> settings.tier(bill.getTotalAmount()).name();
            default -> VendorBillReader.REASON_NO_DELIVERY_RECORDED;
        };
    }

    /**
     * The completed form of the review items among {@code page}, by item id: their actions, the reason a waiting bill
     * gives, and the approval preview of each approvable bill. Items of other types are not in the result.
     */
    public @NonNull Map<String, WorkItem> complete(@NonNull List<WorkItem> page) {
        List<WorkItem> review =
                page.stream().filter(item -> REVIEW_TYPES.contains(item.type())).toList();
        if (review.isEmpty()) {
            return Map.of();
        }
        Map<UUID, VendorBill> bills = new HashMap<>();
        vendorBills
                .findAllById(review.stream()
                        .map(item -> item.routing().get(ROUTING_BILL))
                        .toList())
                .forEach(bill -> bills.put(bill.getVendorBillId(), bill));
        Set<UUID> matched = invoiceMatched(bills.values());
        String caller = VendorBillDecisions.callerOrNull();
        Set<UUID> creatorFirstVendors = vendorCopies.creatorsFirstBillVendors(
                bills.values().stream().map(VendorBill::getVendorId).distinct().toList(), caller);
        ApApprovalPolicy.Settings settings = policy.settings();

        Map<String, WorkItem> completed = new HashMap<>();
        for (WorkItem item : review) {
            VendorBill bill = bills.get(item.routing().get(ROUTING_BILL));
            if (bill == null) {
                continue; // vanished between the two passes
            }
            VendorBillReview.Channel channel = VendorBillReader.channelOf(bill);
            boolean awaitsInvoice = VendorBillReader.awaitsInvoice(channel, matched.contains(bill.getVendorBillId()));
            boolean pick = item.type() == WorkItemType.BILL_PICK_MATCH;
            List<WorkItemAction> actions = actions(
                    VendorBillReader.availableActions(
                            bill.getStatus(),
                            channel,
                            pick,
                            awaitsInvoice,
                            false,
                            false,
                            VendorBillReader.blocks(bill, settings, creatorFirstVendors.contains(bill.getVendorId()))),
                    shown(item.type()));
            String reason = item.type() == WorkItemType.BILL_WAITING_ON_DELIVERY && awaitsInvoice
                    ? VendorBillReader.REASON_INVOICE_NOT_MATCHED
                    : item.reasonCode();
            boolean canApprove = item.type() == WorkItemType.BILL_AWAITING_APPROVAL
                    && actions.stream()
                            .anyMatch(action -> VendorBillAction.APPROVE.name().equals(action.action()));
            List<WorkItemPostingLine> preview = canApprove ? approvalPreview.preview(bill) : List.of();
            completed.put(item.itemId(), item.completed(reason, actions, preview));
        }
        return completed;
    }

    private static Set<VendorBillAction> shown(WorkItemType type) {
        return switch (type) {
            case BILL_PICK_MATCH -> EnumSet.of(VendorBillAction.SELECT_CANDIDATE);
            case BILL_WAITING_ON_DELIVERY -> EnumSet.of(VendorBillAction.SUBMIT_FOR_APPROVAL);
            case BILL_AWAITING_APPROVAL -> EnumSet.of(VendorBillAction.APPROVE, VendorBillAction.REJECT);
            default -> EnumSet.allOf(VendorBillAction.class);
        };
    }

    /** The goods-receipt bills among {@code bills} a vendor invoice is matched to; one read of evidence, one of lines. */
    private Set<UUID> invoiceMatched(java.util.Collection<VendorBill> bills) {
        List<UUID> receiptBills = bills.stream()
                .filter(bill -> VendorBillReader.channelOf(bill) == VendorBillReview.Channel.GOODS_RECEIPT)
                .map(VendorBill::getVendorBillId)
                .toList();
        Set<UUID> matched = new HashSet<>();
        if (receiptBills.isEmpty()) {
            return matched;
        }
        Map<UUID, VendorBillMatchEvidence> latest = new HashMap<>();
        for (VendorBillMatchEvidence row : evidence.findByVendorBillIdIn(receiptBills)) {
            latest.merge(
                    row.getVendorBillId(),
                    row,
                    (a, b) -> Comparator.comparing(VendorBillMatchEvidence::getRecordedAt)
                                            .thenComparing(VendorBillMatchEvidence::getMatchEvidenceId)
                                            .compare(a, b)
                                    >= 0
                            ? a
                            : b);
        }
        Map<UUID, List<VendorBillLine>> lines = new HashMap<>();
        for (VendorBillLine line : billLines.findByVendorBill_VendorBillIdIn(receiptBills)) {
            lines.computeIfAbsent(line.getVendorBill().getVendorBillId(), k -> new ArrayList<>())
                    .add(line);
        }
        for (UUID id : receiptBills) {
            List<VendorBillLine> stored = new ArrayList<>(lines.getOrDefault(id, List.of()));
            stored.sort(Comparator.comparing(VendorBillLine::getLineNumber));
            if (VendorBillReader.invoiceMatched(latest.get(id), stored)) {
                matched.add(id);
            }
        }
        return matched;
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
        Set<UUID> held = vendorCopies
                .heldVendors(due.stream()
                        .map(open -> open.bill().getVendorId())
                        .distinct()
                        .toList())
                .keySet();
        List<WorkItem> items = new ArrayList<>();
        for (OpenBill open : due) {
            VendorBill bill = open.bill();
            LocalDate dueDate = open.dueDate();
            boolean selfApproved = caller != null
                    && bill.getApprovedByKind() == VendorBillApproverKind.PERSON
                    && caller.equals(bill.getApprovedBy())
                    && !settings.allowApproverPayment();
            WorkItemAction pay = held.contains(bill.getVendorId())
                    ? WorkItemAction.blocked(PAY, BLOCKED_AP_HOLD)
                    : selfApproved ? WorkItemAction.blocked(PAY, BLOCKED_SELF_APPROVED) : WorkItemAction.allowed(PAY);
            items.add(WorkItems.item(
                    WorkItemType.BILLS_DUE_SOON,
                    bill.getVendorBillId().toString(),
                    WorkItems.references(
                            "vendor", bill.getVendorName(),
                            "billNumber", bill.getBillNumber(),
                            "purchaseOrderNumber", bill.getPurchaseOrderNumber(),
                            "dueDate", dueDate.toString(),
                            "estimated", Boolean.toString(open.estimated()),
                            "termsSource",
                                    open.estimate() == null
                                            ? null
                                            : open.estimate().termsSource().name()),
                    dueDate.isBefore(today) ? "OVERDUE" : "DUE_SOON",
                    open.openAmount(),
                    bill.getStatus().name(),
                    dueDate,
                    List.of(pay),
                    List.of(),
                    WorkItems.routing(ROUTING_BILL, bill.getVendorBillId(), "vendorId", bill.getVendorId())));
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

    private static Map<String, String> billReferences(VendorBill bill) {
        return WorkItems.references(
                "vendor", bill.getVendorName(),
                "billNumber", bill.getBillNumber(),
                "purchaseOrderNumber", bill.getPurchaseOrderNumber(),
                "billDate",
                        bill.getBillDate() == null
                                ? null
                                : bill.getBillDate().toLocalDate().toString());
    }

    /** When the bill started waiting: its submission for an approval, else its arrival; its bill date as a last resort. */
    private LocalDate reviewDate(VendorBill bill) {
        Instant at = bill.getStatus() == VendorBillStatus.AWAITING_APPROVAL && bill.getSubmittedAt() != null
                ? bill.getSubmittedAt()
                : bill.getCreatedAt();
        if (at != null) {
            return zoneResolver.postingDate(at);
        }
        return bill.getBillDate() == null
                ? zoneResolver.today()
                : bill.getBillDate().toLocalDate();
    }
}
