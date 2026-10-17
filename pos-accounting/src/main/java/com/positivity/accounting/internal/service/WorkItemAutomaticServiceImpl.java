package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.AutomaticPaymentApplicationRow;
import com.positivity.accounting.internal.dto.AutomaticPaymentApplicationsPage;
import com.positivity.accounting.internal.dto.AutomaticWorkItem;
import com.positivity.accounting.internal.dto.AutomaticWorkItemsResponse;
import com.positivity.accounting.internal.dto.WorkItemAction;
import com.positivity.accounting.internal.entity.ProcessorSettlementLine;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.AutomaticWorkItemKind;
import com.positivity.accounting.internal.enums.SettlementLineMatchStatus;
import com.positivity.accounting.internal.enums.VendorBillApproverKind;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.ProcessorSettlementLineRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.security.AccountingPermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the "done automatically" list on every request (CAP:550 S19, #2515): the payment applications read through
 * {@link AutomaticPaymentApplicationQueryService} in-process (this read builds no payment source of its own), the
 * settlement lines the automatic matcher matched since, and the bills the system approver approved since. Only a payment
 * application can be undone, by the holders of {@code accounting:payment:reverse}; an approved bill is locked and a
 * settlement match has no undo (P4). The look-back is the 31 days the ruling of 2026-10-17 allows, on the tenant
 * accounting date.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkItemAutomaticServiceImpl implements WorkItemAutomaticService {

    static final int APPLICATION_PAGE_SIZE = 100;
    static final String STATUS_APPLIED = "APPLIED";
    static final String STATUS_REVERSED = "REVERSED";
    static final String STATUS_MATCHED = "MATCHED";

    private final AutomaticPaymentApplicationQueryService applications;
    private final ProcessorSettlementLineRepository settlementLines;
    private final JournalEntryRepository journalEntries;
    private final VendorBillRepository vendorBills;
    private final WorkItemPaging paging;
    private final AccountingCalendarZoneResolver zoneResolver;
    private final Clock clock;

    @Override
    public @NonNull AutomaticWorkItemsResponse list(
            @Nullable String since, @Nullable String cursor, @Nullable Integer limit) {
        int size = paging.limit(limit);
        Instant from = resolveSince(since);
        String scope = "since:" + from;
        WorkItemPaging.Key after = paging.decode(cursor, scope);

        List<AutomaticWorkItem> items = new ArrayList<>();
        // Each kind is shown to those who hold a permission over what it lists (ruling of the review of #2752).
        if (WorkItems.hasAny(AccountingPermissions.PAYMENT_APPLY, AccountingPermissions.PAYMENT_REVERSE)) {
            items.addAll(paymentApplications(from));
        }
        if (WorkItems.has(AccountingPermissions.RECONCILIATION_VIEW)) {
            items.addAll(settlementMatches(from));
        }
        if (WorkItems.hasAny(AccountingPermissions.AP_APPROVE, AccountingPermissions.AP_APPROVE_OVER_LIMIT)) {
            items.addAll(systemApprovals(from));
        }
        WorkItemPaging.Slice<AutomaticWorkItem> page =
                paging.slice(items, AutomaticWorkItem::occurredOn, AutomaticWorkItem::itemId, scope, after, size);
        return new AutomaticWorkItemsResponse(page.items(), page.nextCursor(), Instant.now(clock));
    }

    private List<AutomaticWorkItem> paymentApplications(Instant since) {
        boolean canUndo = WorkItems.has(AccountingPermissions.PAYMENT_REVERSE);
        List<AutomaticWorkItem> items = new ArrayList<>();
        int page = 0;
        while (true) {
            AutomaticPaymentApplicationsPage rows =
                    applications.listAutomatic(since, page, APPLICATION_PAGE_SIZE, canUndo);
            List<AutomaticPaymentApplicationRow> content = rows.getItems() == null ? List.of() : rows.getItems();
            for (AutomaticPaymentApplicationRow row : content) {
                items.add(application(row, canUndo));
            }
            page++;
            if (content.isEmpty() || page >= rows.getTotalPages()) {
                return items;
            }
        }
    }

    private AutomaticWorkItem application(AutomaticPaymentApplicationRow row, boolean canUndo) {
        // A reversed application cannot be undone again, and UNDO is only the reverse permission's to offer.
        List<WorkItemAction> actions = row.isReversed() || !canUndo || row.getActions() == null
                ? List.of()
                : row.getActions().stream().map(WorkItemAction::allowed).toList();
        return new AutomaticWorkItem(
                AutomaticWorkItemKind.PAYMENT_APPLIED_AUTOMATICALLY.name() + ":" + row.getPaymentApplicationId(),
                AutomaticWorkItemKind.PAYMENT_APPLIED_AUTOMATICALLY,
                zoneResolver.postingDate(row.getAppliedAt()),
                WorkItems.references(
                        "customer", row.getCustomerDisplayName(),
                        "paymentReference", row.getCustomerReference(),
                        "invoiceNumber", row.getInvoiceNumber(),
                        "source",
                                row.getSource() == null ? null : row.getSource().name()),
                row.getAppliedAmount(),
                row.isReversed() ? STATUS_REVERSED : STATUS_APPLIED,
                actions,
                WorkItems.routing(
                        "paymentApplicationId", row.getPaymentApplicationId(),
                        "paymentId", row.getPaymentId(),
                        "invoiceId", row.getInvoiceId()));
    }

    /**
     * The lines the automatic matcher matched at or after {@code since}. The matcher decides a line as it creates it, so
     * the creation time is the match time; a line matched by hand is told apart by the reclass entry that match posts
     * (the automatic matcher posts none), and is left out.
     */
    private List<AutomaticWorkItem> settlementMatches(Instant since) {
        List<ProcessorSettlementLine> matched =
                settlementLines.findByMatchStatusAndCreatedAtGreaterThanEqual(SettlementLineMatchStatus.MATCHED, since);
        if (matched.isEmpty()) {
            return List.of();
        }
        Set<UUID> byHand = new HashSet<>(journalEntries.findSourceEventIdsIn(matched.stream()
                .map(line -> SettlementReconciliationServiceImpl.reclassSourceEventId(line.getLineId()))
                .toList()));
        List<AutomaticWorkItem> items = new ArrayList<>();
        for (ProcessorSettlementLine line : matched) {
            if (byHand.contains(SettlementReconciliationServiceImpl.reclassSourceEventId(line.getLineId()))) {
                continue;
            }
            items.add(new AutomaticWorkItem(
                    AutomaticWorkItemKind.SETTLEMENT_LINE_MATCHED.name() + ":" + line.getLineId(),
                    AutomaticWorkItemKind.SETTLEMENT_LINE_MATCHED,
                    zoneResolver.postingDate(line.getCreatedAt()),
                    WorkItems.references(
                            "settlement", line.getSettlementId(),
                            "paymentReference", line.getPaymentReference(),
                            "lineType", line.getLineType() == null ? null : line.getLineType().name()),
                    line.getNetAmount(),
                    STATUS_MATCHED,
                    List.of(),
                    WorkItems.routing("settlementLineId", line.getLineId(), "paymentId", line.getMatchedPaymentId())));
        }
        return items;
    }

    private List<AutomaticWorkItem> systemApprovals(Instant since) {
        List<AutomaticWorkItem> items = new ArrayList<>();
        for (VendorBill bill :
                vendorBills.findByApprovedByKindAndApprovedAtGreaterThanEqualOrderByApprovedAtAscVendorBillIdAsc(
                        VendorBillApproverKind.SYSTEM, since)) {
            items.add(new AutomaticWorkItem(
                    AutomaticWorkItemKind.BILL_APPROVED_BY_SYSTEM.name() + ":" + bill.getVendorBillId(),
                    AutomaticWorkItemKind.BILL_APPROVED_BY_SYSTEM,
                    zoneResolver.postingDate(bill.getApprovedAt()),
                    WorkItems.references(
                            "vendor", bill.getVendorName(),
                            "billNumber", bill.getBillNumber(),
                            "purchaseOrderNumber", bill.getPurchaseOrderNumber()),
                    bill.getTotalAmount(),
                    bill.getStatus().name(),
                    List.of(),
                    WorkItems.routing("vendorBillId", bill.getVendorBillId(), "vendorId", bill.getVendorId())));
        }
        return items;
    }

    /** {@code since} as an instant: the start of today in the tenant's zone when none; refuses the future and the far past. */
    @NonNull
    Instant resolveSince(@Nullable String since) {
        ZoneId zone = zoneResolver.zone();
        LocalDate today = zoneResolver.today();
        if (since == null || since.isBlank()) {
            return today.atStartOfDay(zone).toInstant();
        }
        Instant parsed = parse(since.trim(), zone);
        if (parsed.isAfter(Instant.now(clock))) {
            throw InvalidRequestParameterException.forField("since", "must not be in the future");
        }
        if (parsed.isBefore(
                today.minusDays(MAX_LOOK_BACK_DAYS).atStartOfDay(zone).toInstant())) {
            throw InvalidRequestParameterException.forField(
                    "since", "must not be more than " + MAX_LOOK_BACK_DAYS + " days back");
        }
        return parsed;
    }

    private static Instant parse(String text, ZoneId zone) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException notAnInstant) {
            try {
                return LocalDate.parse(text).atStartOfDay(zone).toInstant();
            } catch (DateTimeParseException notADate) {
                throw InvalidRequestParameterException.forField(
                        "since", "must be an ISO instant or a date, such as 2026-10-17");
            }
        }
    }
}
