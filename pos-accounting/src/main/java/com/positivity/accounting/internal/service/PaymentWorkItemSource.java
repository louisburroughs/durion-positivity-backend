package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.UnappliedPaymentRow;
import com.positivity.accounting.internal.dto.UnappliedPaymentsPage;
import com.positivity.accounting.internal.dto.UnpaidWalkInSalesResponse;
import com.positivity.accounting.internal.dto.WalkInOpenInvoice;
import com.positivity.accounting.internal.dto.WorkItem;
import com.positivity.accounting.internal.dto.WorkItemAction;
import com.positivity.accounting.internal.dto.WorkItemPostingLine;
import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.enums.WorkItemType;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.security.AccountingPermissions;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The PAYMENTS work items (CAP:550 S19, #2515; SPEC-accounting-workspace §7.1 "Work items"): customer payments with a
 * suggested match, unpaid walk-in sales past their business day, and vendor payments that did not reach the ledger.
 * Each type is shown to the holders of its own permission.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentWorkItemSource {

    /** Rows per page read from the receivables worklist. */
    static final int WORKLIST_PAGE_SIZE = 100;

    /** {@code blockedReason} of {@code RETRY} while the payment's period is closed and the caller cannot override. */
    static final String BLOCKED_PERIOD_CLOSED = "PERIOD_CLOSED";

    /** {@code blockedReason} of {@code RETRY} while the payment's date is hard-locked and the caller cannot override. */
    static final String BLOCKED_PERIOD_HARD_LOCKED = "PERIOD_HARD_LOCKED";

    static final String RETRY = "RETRY";
    static final String MATCH = "MATCH";
    static final String REASON_OVERPAID = "OVERPAID";
    static final String REASON_SUGGESTED_MATCH = "SUGGESTED_MATCH";
    static final String REASON_BUSINESS_DAY_ENDED = "BUSINESS_DAY_ENDED";
    static final String REASON_POST_FAILED = "GL_POST_FAILED";

    private final ReceivablesWorklistService receivables;
    private final UnpaidWalkInSalesService walkInSales;
    private final APPaymentRepository apPayments;
    private final GLAccountRepository glAccounts;
    private final GLMappingResolver glMappingResolver;
    private final AccountingPeriodGate periodGate;
    private final AccountingCalendarZoneResolver zoneResolver;

    /** The caller's payments items, unordered. */
    public @NonNull List<WorkItem> items() {
        List<WorkItem> items = new ArrayList<>();
        if (WorkItems.has(AccountingPermissions.PAYMENT_APPLY)) {
            items.addAll(paymentsToMatch());
        }
        if (WorkItems.has(AccountingPermissions.REPORTING_VIEW_FINANCIAL_STATEMENTS)) {
            items.addAll(unpaidWalkInSales());
        }
        if (WorkItems.has(AccountingPermissions.JE_POST)) {
            items.addAll(paymentsNotPosted());
        }
        return items;
    }

    private List<WorkItem> paymentsToMatch() {
        List<WorkItem> items = new ArrayList<>();
        int page = 0;
        while (true) {
            UnappliedPaymentsPage rows = receivables.listUnappliedPayments(null, page, WORKLIST_PAGE_SIZE);
            List<UnappliedPaymentRow> content = rows.getItems() == null ? List.of() : rows.getItems();
            for (UnappliedPaymentRow row : content) {
                if (row.getSuggestion() != null) {
                    items.add(paymentToMatch(row));
                }
            }
            page++;
            if (content.isEmpty() || page >= rows.getTotalPages()) {
                return items;
            }
        }
    }

    private WorkItem paymentToMatch(UnappliedPaymentRow row) {
        BigDecimal leftOver = row.getSuggestion().getLeftOver();
        return WorkItems.item(
                WorkItemType.PAYMENT_TO_MATCH,
                row.getPaymentId().toString(),
                WorkItems.references(
                        "customer", row.getCustomerDisplayName(),
                        "paymentReference", row.getCustomerReference(),
                        "paymentMethod", row.getPaymentMethod(),
                        "invoiceNumber", row.getSourceInvoiceNumber()),
                leftOver != null && leftOver.signum() > 0 ? REASON_OVERPAID : REASON_SUGGESTED_MATCH,
                row.getUnappliedAmount(),
                "AVAILABLE",
                row.getReceivedAt() == null ? zoneResolver.today() : zoneResolver.postingDate(row.getReceivedAt()),
                List.of(WorkItemAction.allowed(MATCH)),
                List.of(),
                WorkItems.routing("paymentId", row.getPaymentId(), "customerId", row.getCustomerId()));
    }

    private List<WorkItem> unpaidWalkInSales() {
        UnpaidWalkInSalesResponse sales = walkInSales.read();
        List<WalkInOpenInvoice> open = sales.getOpenInvoices() == null ? List.of() : sales.getOpenInvoices();
        List<WorkItem> items = new ArrayList<>();
        for (WalkInOpenInvoice invoice : open) {
            if (!invoice.isBusinessDayEnded()
                    || invoice.getBalanceDue() == null
                    || invoice.getBalanceDue().signum() <= 0) {
                continue;
            }
            items.add(WorkItems.item(
                    WorkItemType.UNPAID_WALK_IN_SALE,
                    invoice.getInvoiceId().toString(),
                    WorkItems.references(
                            "invoiceNumber", invoice.getInvoiceNumber(),
                            "location", invoice.getLocationCode(),
                            "saleDate",
                                    invoice.getSaleDate() == null
                                            ? null
                                            : invoice.getSaleDate().toString()),
                    REASON_BUSINESS_DAY_ENDED,
                    invoice.getBalanceDue(),
                    "OPEN",
                    invoice.getSaleDate() == null ? zoneResolver.today() : invoice.getSaleDate(),
                    List.of(),
                    List.of(),
                    WorkItems.routing("invoiceId", invoice.getInvoiceId(), "locationId", invoice.getLocationId())));
        }
        return items;
    }

    private List<WorkItem> paymentsNotPosted() {
        List<APPayment> failed = apPayments.findByStatusOrderByPaymentDateAscPaymentIdAsc(APPaymentStatus.GL_POST_FAILED);
        if (failed.isEmpty()) {
            return List.of();
        }
        boolean mayOverride = WorkItems.has(AccountingPermissions.PERIOD_OVERRIDE);
        List<WorkItem> items = new ArrayList<>();
        for (APPayment payment : failed) {
            LocalDate date = payment.getPaymentDate() == null ? zoneResolver.today() : payment.getPaymentDate();
            WorkItemAction retry = WorkItemAction.allowed(RETRY);
            if (!mayOverride) {
                if (periodGate.isHardLocked(date)) {
                    retry = WorkItemAction.blocked(RETRY, BLOCKED_PERIOD_HARD_LOCKED);
                } else if (periodGate.isPostingBlocked(date)) {
                    retry = WorkItemAction.blocked(RETRY, BLOCKED_PERIOD_CLOSED);
                }
            }
            items.add(WorkItems.item(
                    WorkItemType.AP_PAYMENT_NOT_POSTED,
                    payment.getPaymentId().toString(),
                    WorkItems.references(
                            "vendor", payment.getVendorName(),
                            "paymentReference", payment.getPaymentRef(),
                            "paymentMethod", payment.getPaymentMethod() == null ? null : payment.getPaymentMethod().name(),
                            "paymentDate", date.toString()),
                    payment.getGlPostError() == null || payment.getGlPostError().isBlank()
                            ? REASON_POST_FAILED
                            : payment.getGlPostError(),
                    payment.getGrossAmount(),
                    payment.getStatus().name(),
                    date,
                    List.of(retry),
                    List.of(),
                    WorkItems.routing(
                            "paymentId", payment.getPaymentId(),
                            "vendorId", payment.getVendorId(),
                            "bankAccountId", payment.getBankAccountId())));
        }
        return items;
    }

    /** The posting previews of the page's {@code AP_PAYMENT_NOT_POSTED} items, by item id; one read of the payments. */
    public @NonNull Map<String, WorkItem> complete(@NonNull List<WorkItem> page) {
        List<WorkItem> pending = page.stream()
                .filter(item -> item.type() == WorkItemType.AP_PAYMENT_NOT_POSTED)
                .toList();
        if (pending.isEmpty()) {
            return Map.of();
        }
        Map<UUID, APPayment> payments = new HashMap<>();
        apPayments
                .findAllById(pending.stream().map(item -> item.routing().get("paymentId")).toList())
                .forEach(payment -> payments.put(payment.getPaymentId(), payment));
        Map<String, WorkItem> completed = new HashMap<>();
        for (WorkItem item : pending) {
            APPayment payment = payments.get(item.routing().get("paymentId"));
            if (payment != null) {
                completed.put(
                        item.itemId(),
                        item.completed(item.reasonCode(), item.actions(), retryPreview(payment)));
            }
        }
        return completed;
    }

    /**
     * Dr 2000 the gross (and 6030 the fee), Cr the payment's bank account, on the date the entry would post: the payment
     * date, or today when that date is hard-locked and the retry defers past it; empty when an account cannot be named.
     */
    private List<WorkItemPostingLine> retryPreview(APPayment payment) {
        if (payment.getBankAccountId() == null || payment.getGrossAmount() == null || payment.getPaymentDate() == null) {
            return List.of();
        }
        LocalDate date =
                periodGate.isHardLocked(payment.getPaymentDate()) ? zoneResolver.today() : payment.getPaymentDate();
        BigDecimal gross = payment.getGrossAmount();
        BigDecimal fee = payment.getFeeAmount() == null ? BigDecimal.ZERO : payment.getFeeAmount();
        Optional<GLAccount> payable = account(APPaymentPreGatewayChecks.ACCOUNTS_PAYABLE_KEY, date);
        Optional<GLAccount> bank = glAccounts.findById(payment.getBankAccountId());
        Optional<GLAccount> fees = fee.signum() > 0 ? account(APPaymentPreGatewayChecks.PAYMENT_FEES_KEY, date) : Optional.empty();
        if (payable.isEmpty() || bank.isEmpty() || (fee.signum() > 0 && fees.isEmpty())) {
            return List.of();
        }
        List<WorkItemPostingLine> lines = new ArrayList<>();
        lines.add(line(payable.get(), gross));
        fees.ifPresent(account -> lines.add(line(account, fee)));
        lines.add(line(bank.get(), gross.add(fee).negate()));
        return lines;
    }

    private Optional<GLAccount> account(String key, LocalDate date) {
        Optional<UUID> id =
                glMappingResolver.findGLAccount(APPaymentPreGatewayChecks.POSTING_CATEGORY, key, date.atStartOfDay());
        return id.flatMap(glAccounts::findById);
    }

    private static WorkItemPostingLine line(GLAccount account, @Nullable BigDecimal signed) {
        return WorkItemPostingLine.ofSigned(account.getAccountCode(), account.getAccountName(), signed);
    }
}
