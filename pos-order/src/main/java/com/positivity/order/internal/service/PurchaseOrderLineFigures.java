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
 *   <li>{@code open = max(0, ordered − received − returned − cancelled)}, stored on the line and published. A line
 *       written before the figures existed ({@code receivedQuantity} null) adopts {@code received = ordered − open}
 *       once, as V14 backfills stored rows; from then on a zero is a zero;
 *   <li>the balance, ex-tax (#2633), moves with each event and never below zero: a receipt takes its accrual off, a
 *       correction puts it back, a reopen puts the quantity's value back, a short-close takes it off; a credited
 *       return leaves it alone;
 *   <li>the status, from the lines, applied only from {@code APPROVED}, {@code PARTIALLY_RECEIVED} and {@code
 *       FULLY_RECEIVED} (#2634): every line open 0 → {@code CLOSED} if anything was short-closed on the order,
 *       else {@code FULLY_RECEIVED}; else something received → {@code PARTIALLY_RECEIVED}; else {@code APPROVED}.
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
     * A line written before the figures existed ({@code receivedQuantity} null) adopts {@code received = ordered −
     * open} (and that quantity's value as its accrual) exactly once: the backfill V14 applied to stored rows, so a
     * fixture or a row the migration did not see derives the same open quantity. A line already carrying figures is
     * left alone, so a figure that later returns to zero stays zero.
     */
    public static void adoptLegacyOpen(@NonNull PurchaseOrderLineEntity line) {
        if (line.getReceivedQuantity() != null) {
            return;
        }
        BigDecimal open = line.getOpenQuantityDecimal();
        BigDecimal ordered = zeroIfNull(line.getQuantityDecimal());
        BigDecimal received = open == null || open.compareTo(ordered) >= 0
                ? BigDecimal.ZERO
                : ordered.subtract(open.signum() < 0 ? BigDecimal.ZERO : open);
        line.setReceivedQuantity(received);
        if (received.signum() > 0) {
            line.setReceivedAccruedMinor(zeroIfNull(line.getReceivedAccruedMinor()) + valueOf(line, received));
        }
    }

    /** Re-derives every line's open quantity from the figures; the status is left alone (a credited return). */
    public static void refreshOpen(@NonNull PurchaseOrderEntity order) {
        for (PurchaseOrderLineEntity line : order.getLines()) {
            adoptLegacyOpen(line);
            line.setOpenQuantityDecimal(open(line));
        }
    }

    /** {@link #refresh(PurchaseOrderEntity, boolean)} with nothing received beyond what the lines say. */
    public static void refresh(@NonNull PurchaseOrderEntity order) {
        refresh(order, false);
    }

    /**
     * Re-derives every line's open quantity from the figures, and the status when the order is in a receiving status.
     *
     * @param receivedOffLines true when the event received something the lines do not carry (a receipt line without
     *     a {@code poLineId}): the order is then at least {@code PARTIALLY_RECEIVED}, as before the figures existed
     */
    public static void refresh(@NonNull PurchaseOrderEntity order, boolean receivedOffLines) {
        boolean allSettled = true;
        boolean anyReceived = receivedOffLines;
        boolean anyCancelled = false;
        for (PurchaseOrderLineEntity line : order.getLines()) {
            adoptLegacyOpen(line);
            BigDecimal open = open(line);
            line.setOpenQuantityDecimal(open);
            allSettled &= open.signum() <= 0;
            anyReceived |= zeroIfNull(line.getReceivedQuantity()).signum() > 0;
            anyCancelled |= zeroIfNull(line.getCancelledQuantity()).signum() > 0;
        }
        if (RECEIVING_STATUSES.contains(order.getStatus())) {
            if (allSettled) {
                order.setStatus(anyCancelled ? PurchaseOrderStatus.CLOSED : PurchaseOrderStatus.FULLY_RECEIVED);
            } else {
                order.setStatus(anyReceived ? PurchaseOrderStatus.PARTIALLY_RECEIVED : PurchaseOrderStatus.APPROVED);
            }
        }
    }

    /** Moves the ex-tax balance by {@code deltaMinor} (negative takes off), never below zero. */
    public static void moveBalance(@NonNull PurchaseOrderEntity order, long deltaMinor) {
        long balance = zeroIfNull(order.getOpenBalanceMinor());
        order.setOpenBalanceMinor(Math.max(0L, balance + deltaMinor));
    }

    /**
     * Whether goods ever arrived on the order: received or returned for credit (gross of returns, net of
     * corrections). An order whose receipts were all returned still had deliveries, so it closes rather than cancels.
     */
    public static boolean anythingReceived(@NonNull PurchaseOrderEntity order) {
        return order.getLines().stream().anyMatch(line -> zeroIfNull(line.getReceivedQuantity())
                        .add(zeroIfNull(line.getReturnedQuantity()))
                        .signum()
                > 0);
    }

    /** Whether every line's open quantity is zero. */
    public static boolean allSettled(@NonNull PurchaseOrderEntity order) {
        return order.getLines().stream().allMatch(line -> open(line).signum() <= 0);
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
