package com.positivity.order.internal.service;

import com.positivity.order.internal.entity.PurchaseOrderEntity;
import com.positivity.order.internal.entity.PurchaseOrderLineEntity;
import com.positivity.order.internal.enums.PurchaseOrderStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.EnumSet;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The per-line figures of a purchase order and what they derive (CAP:550 #2635, Order ruling on #2598):
 *
 * <ul>
 *   <li>{@code open = max(0, ordered − received − returned − cancelled)}, stored on the line and published;
 *   <li>the balance, ex-tax (#2633): {@code max(0, Σ lineTotal − Σ receivedAccrued − Σ returnedValue − Σ
 *       cancelledValue − unallocatedAccrued)}, the last term being receipts that named no line of the order;
 *   <li>the status, from the lines, applied only from {@code APPROVED}, {@code PARTIALLY_RECEIVED} and {@code
 *       FULLY_RECEIVED} (#2634): every line open 0 → {@code CLOSED} if anything was short-closed on the order,
 *       else {@code FULLY_RECEIVED}; else Σ received > 0 → {@code PARTIALLY_RECEIVED}; else {@code APPROVED}.
 * </ul>
 *
 * <p>A quantity's value is {@code lineTotalMinor × quantity ÷ ordered}, HALF_UP to whole minor units: the accrued
 * unit price per base unit, applied to the quantity moved.
 */
public final class PurchaseOrderLineFigures {

    /** The statuses a receipt, return, reopen or line cancel may move along; every other status is kept (#2634). */
    public static final Set<PurchaseOrderStatus> RECEIVING_STATUSES = EnumSet.of(
            PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.PARTIALLY_RECEIVED, PurchaseOrderStatus.FULLY_RECEIVED);

    private PurchaseOrderLineFigures() {}

    /** {@code lineTotalMinor × quantity ÷ ordered}, HALF_UP; zero for a line that ordered nothing. */
    public static long valueOf(@NonNull PurchaseOrderLineEntity line, @NonNull BigDecimal quantity) {
        BigDecimal ordered = line.getQuantityDecimal();
        if (ordered == null || ordered.signum() <= 0 || line.getLineTotalMinor() == null) {
            return 0L;
        }
        return BigDecimal.valueOf(line.getLineTotalMinor())
                .multiply(quantity)
                .divide(ordered, 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** The open quantity the line's figures derive: never negative. */
    public static @NonNull BigDecimal open(@NonNull PurchaseOrderLineEntity line) {
        BigDecimal open = zeroIfNull(line.getQuantityDecimal())
                .subtract(zeroIfNull(line.getReceivedQuantity()))
                .subtract(zeroIfNull(line.getReturnedQuantity()))
                .subtract(zeroIfNull(line.getCancelledQuantity()));
        return open.signum() < 0 ? BigDecimal.ZERO : open;
    }

    /**
     * Re-derives every line's open quantity and the order's balance from the figures, and the status when the
     * order is in a receiving status.
     */
    public static void refresh(@NonNull PurchaseOrderEntity order) {
        long total = 0L;
        long taken = 0L;
        boolean allSettled = true;
        boolean anyReceived = false;
        boolean anyCancelled = false;
        for (PurchaseOrderLineEntity line : order.getLines()) {
            BigDecimal open = open(line);
            line.setOpenQuantityDecimal(open);
            total += line.getLineTotalMinor() == null ? 0L : line.getLineTotalMinor();
            taken += zeroIfNull(line.getReceivedAccruedMinor())
                    + zeroIfNull(line.getReturnedValueMinor())
                    + zeroIfNull(line.getCancelledValueMinor());
            allSettled &= open.signum() <= 0;
            anyReceived |= zeroIfNull(line.getReceivedQuantity()).signum() > 0;
            anyCancelled |= zeroIfNull(line.getCancelledQuantity()).signum() > 0;
        }
        long unallocated = zeroIfNull(order.getUnallocatedAccruedMinor());
        order.setOpenBalanceMinor(Math.max(0L, total - taken - unallocated));
        if (RECEIVING_STATUSES.contains(order.getStatus())) {
            if (allSettled) {
                order.setStatus(anyCancelled ? PurchaseOrderStatus.CLOSED : PurchaseOrderStatus.FULLY_RECEIVED);
            } else {
                order.setStatus(anyReceived ? PurchaseOrderStatus.PARTIALLY_RECEIVED : PurchaseOrderStatus.APPROVED);
            }
        }
    }

    /** Whether anything was received on the order (net of corrections). */
    public static boolean anythingReceived(@NonNull PurchaseOrderEntity order) {
        return order.getLines().stream().anyMatch(line -> zeroIfNull(line.getReceivedQuantity()).signum() > 0);
    }

    /** A decimal at the currency exponent, in whole minor units of {@code currencyCode} (HALF_UP). */
    public static long toMinor(@Nullable BigDecimal amount, @Nullable String currencyCode) {
        if (amount == null) {
            return 0L;
        }
        int exponent = 2;
        if (currencyCode != null && !currencyCode.isBlank()) {
            try {
                exponent = Math.max(0, Currency.getInstance(currencyCode.trim()).getDefaultFractionDigits());
            } catch (IllegalArgumentException ignored) {
                // An unknown code keeps the usual two places; the amount is still applied.
            }
        }
        return amount.movePointRight(exponent).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    static @NonNull BigDecimal zeroIfNull(@Nullable BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    static long zeroIfNull(@Nullable Long value) {
        return value == null ? 0L : value;
    }
}
