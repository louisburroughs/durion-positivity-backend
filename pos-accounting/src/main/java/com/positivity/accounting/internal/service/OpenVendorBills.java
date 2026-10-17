package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.repository.APPaymentAllocationRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.service.EstimatedDueDateService.EstimatedDueDate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The open vendor bills the cash outlook and the bills work items read (CAP:550 S19, #2515; SPEC-accounting-workspace
 * §4.2; AW11): one rule for what is open and when it falls due, shared so the outlook, the bills lane and the
 * "bills due soon" items cannot drift.
 *
 * <p>A bill is open for {@code totalAmount} minus its live allocations, as the aged payables report computes it, in the
 * ledger currency only (ADR-0067 PC-9); a bill with nothing open is left out. {@code APPROVED} bills are the committed
 * ones: each carries its real due date, or the {@link EstimatedDueDateService} estimate when it has none. Bills
 * awaiting approval, pending a receipt match or in match exception are the uncommitted ones and carry no due date.
 * {@code CURRENCY_HOLD}, {@code VOIDED}, {@code REJECTED} and {@code PAID} bills are in neither list. A negative open
 * amount is a vendor credit note; it is in the list for its caller to treat apart. Read-only: nothing is written to a
 * bill, the estimate included.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OpenVendorBills {

    /** The bills that count as committed. */
    static final Set<VendorBillStatus> COMMITTED = Set.of(VendorBillStatus.APPROVED);

    /** The bills not yet approved, kept apart from the committed ones (AW11). */
    static final Set<VendorBillStatus> UNAPPROVED = Set.of(
            VendorBillStatus.AWAITING_APPROVAL,
            VendorBillStatus.PENDING_RECEIPT_MATCH,
            VendorBillStatus.MATCH_EXCEPTION);

    private final VendorBillRepository vendorBills;
    private final APPaymentAllocationRepository allocations;
    private final EstimatedDueDateService estimatedDueDates;
    private final LedgerCurrency ledgerCurrency;

    /**
     * An open bill and its open amount; for an approved bill, its real due date or its estimate.
     *
     * @param openAmount total minus allocations at the currency's precision; negative for a credit note
     * @param realDueDate the bill's own due date; null when it has none
     * @param estimate the estimate, present exactly when an approved bill has no real due date
     */
    public record OpenBill(
            @NonNull VendorBill bill,
            @NonNull BigDecimal openAmount,
            @Nullable LocalDate realDueDate,
            @Nullable EstimatedDueDate estimate) {

        /** The day the bill falls due: its real due date, else its estimate; null for a bill not approved. */
        public @Nullable LocalDate dueDate() {
            if (realDueDate != null) {
                return realDueDate;
            }
            return estimate == null ? null : estimate.dueDate();
        }

        /** Whether the due date is an estimate. */
        public boolean estimated() {
            return realDueDate == null && estimate != null;
        }
    }

    /** The open bills: the approved ones with their due dates, and those not yet approved. */
    public record Open(
            @NonNull List<OpenBill> approved, @NonNull List<OpenBill> unapproved) {}

    /** Every open bill, in one pass: one query for the bills, one for their allocations, and the estimate's own. */
    public @NonNull Open load() {
        Set<VendorBillStatus> statuses = new HashSet<>(COMMITTED);
        statuses.addAll(UNAPPROVED);
        List<VendorBill> bills = vendorBills.findByStatusIn(statuses).stream()
                .filter(bill -> !ledgerCurrency.isForeign(bill.getCurrency()))
                .toList();
        if (bills.isEmpty()) {
            return new Open(List.of(), List.of());
        }
        Map<UUID, BigDecimal> allocated =
                allocations
                        .sumAllocatedAmountByVendorBillIdIn(
                                bills.stream().map(VendorBill::getVendorBillId).toList())
                        .stream()
                        .collect(Collectors.toMap(
                                APPaymentAllocationRepository.VendorBillAllocationSum::getVendorBillId,
                                APPaymentAllocationRepository.VendorBillAllocationSum::getAllocated));
        Map<VendorBill, EstimatedDueDate> estimates = estimatedDueDates.estimateAll(bills.stream()
                .filter(bill -> COMMITTED.contains(bill.getStatus()))
                .toList());
        String currency = ledgerCurrency.code();
        List<OpenBill> approved = new ArrayList<>();
        List<OpenBill> unapproved = new ArrayList<>();
        for (VendorBill bill : bills) {
            BigDecimal total = bill.getTotalAmount() == null ? BigDecimal.ZERO : bill.getTotalAmount();
            BigDecimal open = InvoiceBalanceCalculator.atCurrencyScale(
                    total.subtract(allocated.getOrDefault(bill.getVendorBillId(), BigDecimal.ZERO)), currency);
            if (open.signum() == 0) {
                continue;
            }
            if (!COMMITTED.contains(bill.getStatus()) && !UNAPPROVED.contains(bill.getStatus())) {
                continue; // held, voided, rejected or paid: neither committed nor pending (AW11)
            }
            if (COMMITTED.contains(bill.getStatus())) {
                LocalDate real =
                        bill.getDueDate() == null ? null : bill.getDueDate().toLocalDate();
                approved.add(new OpenBill(bill, open, real, real == null ? estimates.get(bill) : null));
            } else {
                unapproved.add(new OpenBill(bill, open, null, null));
            }
        }
        return new Open(approved, unapproved);
    }
}
