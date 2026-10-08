package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.VendorBill;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Whether a vendor's own header totals add up: gross = net + tax (CAP:550 S12, #2509; AW47). Accounts payable is
 * always credited the gross; a gap within the rounding tolerance, 0.01 per stated line and at most 0.05 per bill (a
 * header-only bill counts as one line), is put on the largest debit and kept as {@code roundingAdjustment}. A larger
 * gap holds the bill in {@code MATCH_EXCEPTION} until a person says where it posts ({@code difference}), corrects or
 * voids it.
 *
 * <p>Only a bill with the vendor's header totals has them: an EDI bill. Its net and tax are stored as stated, signed
 * like the gross; with only the gross and tax stated the net is gross - tax, with only the gross and net the tax is
 * gross - net, and with the gross alone the tax is 0 ({@link SupplierInvoiceEventsListener}), so the gap is non-zero
 * only when all three were stated.
 *
 * @param gross the billed gross, the payable
 * @param net the stated net
 * @param tax the stated tax
 * @param difference {@code gross - (net + tax)}
 * @param tolerance the rounding tolerance of this bill
 */
record VendorBillTotals(
        @NonNull BigDecimal gross,
        @NonNull BigDecimal net,
        @NonNull BigDecimal tax,
        @NonNull BigDecimal difference,
        @NonNull BigDecimal tolerance) {

    static final BigDecimal TOLERANCE_PER_LINE = new BigDecimal("0.01");
    static final BigDecimal TOLERANCE_PER_BILL = new BigDecimal("0.05");

    private static final int SCALE = 2;

    /** The totals of {@code bill}; empty for a bill without the vendor's header totals (a goods-receipt bill). */
    static @NonNull Optional<VendorBillTotals> of(@NonNull VendorBill bill) {
        if (bill.getTotalAmount() == null || bill.getNetAmount() == null) {
            return Optional.empty();
        }
        BigDecimal gross = scaled(bill.getTotalAmount());
        BigDecimal net = scaled(bill.getNetAmount());
        BigDecimal tax = scaled(bill.getTaxAmount());
        return Optional.of(new VendorBillTotals(
                gross, net, tax, gross.subtract(net).subtract(tax), tolerance(bill.getStatedLineCount())));
    }

    /** 0.01 per stated line, at most 0.05; a bill stating no lines counts as one. */
    static @NonNull BigDecimal tolerance(@Nullable Integer statedLines) {
        int lines = statedLines == null || statedLines < 1 ? 1 : statedLines;
        return TOLERANCE_PER_LINE.multiply(BigDecimal.valueOf(lines)).min(TOLERANCE_PER_BILL);
    }

    /** Whether the gap is within the tolerance: posted as a rounding adjustment, no decision needed. */
    boolean reconciled() {
        return difference.abs().compareTo(tolerance) <= 0;
    }

    /** The {@code statusExplanation} of a bill held for its totals. */
    @NonNull
    String explanation() {
        return "The vendor's totals don't add up: net " + net.toPlainString() + " + tax " + tax.toPlainString()
                + " ≠ total " + gross.toPlainString();
    }

    private static BigDecimal scaled(@Nullable BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO.setScale(SCALE) : amount.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
