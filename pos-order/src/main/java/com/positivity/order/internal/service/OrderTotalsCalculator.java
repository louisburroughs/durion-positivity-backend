package com.positivity.order.internal.service;

import com.positivity.order.internal.entity.OrderDiscountType;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderLine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Recomputes the order's monetary aggregates from its lines (parity story B2, spec R3.6–R3.10).
 *
 * <p>Definitions (documented in OpenAPI): {@code lineSubtotal} = extended amount after the line
 * discount; {@code subtotal} = Σ lineSubtotal (pre-order-discount, pre-tax); the order discount is
 * allocated pro-rata across lines by lineSubtotal, HALF_UP per line with the residual cent(s)
 * corrected on the largest line so Σ allocations equals the order discount exactly;
 * {@code discountTotal} = Σ line discounts + allocated order discount;
 * {@code lineTotal} = lineSubtotal − allocation + line tax; {@code grandTotal} = subtotal − order
 * discount + taxTotal. Tax amounts are read, never derived, here — pos-tax owns them (story B3).
 */
@Component
public class OrderTotalsCalculator {

    private static final int SCALE = 4;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public void recalculate(@NonNull SalesOrder order) {
        List<SalesOrderLine> lines =
                order.getLines().stream().filter(Objects::nonNull).toList();

        BigDecimal subtotal = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal lineDiscounts = BigDecimal.ZERO;
        for (SalesOrderLine line : lines) {
            BigDecimal gross = line.getUnitPrice()
                    .multiply(BigDecimal.valueOf(line.getQuantity()))
                    .setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal lineDiscount = line.getDiscountPercent() == null
                    ? BigDecimal.ZERO
                    : gross.multiply(line.getDiscountPercent()).divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
            line.setLineSubtotal(gross.subtract(lineDiscount));
            subtotal = subtotal.add(line.getLineSubtotal());
            lineDiscounts = lineDiscounts.add(lineDiscount);
        }

        BigDecimal orderDiscount = orderDiscountTotal(order, subtotal);
        allocateOrderDiscount(lines, subtotal, orderDiscount, lineDiscounts);

        BigDecimal taxTotal = lines.stream()
                .map(SalesOrderLine::getTaxAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(SCALE, RoundingMode.HALF_UP);

        order.setSubtotal(subtotal);
        order.setDiscountTotal(lineDiscounts.add(orderDiscount).setScale(SCALE, RoundingMode.HALF_UP));
        order.setTaxTotal(taxTotal);
        order.setGrandTotal(subtotal.subtract(orderDiscount).add(taxTotal).setScale(SCALE, RoundingMode.HALF_UP));
    }

    private BigDecimal orderDiscountTotal(SalesOrder order, BigDecimal subtotal) {
        if (order.getOrderDiscountType() == null || order.getOrderDiscountValue() == null || subtotal.signum() <= 0) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal discount = order.getOrderDiscountType() == OrderDiscountType.PERCENT
                ? subtotal.multiply(order.getOrderDiscountValue()).divide(HUNDRED, SCALE, RoundingMode.HALF_UP)
                : order.getOrderDiscountValue().setScale(SCALE, RoundingMode.HALF_UP);
        return discount.min(subtotal).max(BigDecimal.ZERO);
    }

    /**
     * Pro-rata allocation with largest-line residual correction (the accounting parity-E1
     * rounding approach): per-line share = round(discount × lineSubtotal / subtotal), then the
     * rounding residual is dumped on the largest line so the shares sum exactly.
     */
    private void allocateOrderDiscount(
            List<SalesOrderLine> lines, BigDecimal subtotal, BigDecimal orderDiscount, BigDecimal lineDiscounts) {
        BigDecimal allocated = BigDecimal.ZERO;
        SalesOrderLine largest = lines.stream()
                .max(Comparator.comparing(SalesOrderLine::getLineSubtotal))
                .orElse(null);

        for (SalesOrderLine line : lines) {
            BigDecimal share = (orderDiscount.signum() == 0 || subtotal.signum() == 0)
                    ? BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP)
                    : orderDiscount.multiply(line.getLineSubtotal()).divide(subtotal, SCALE, RoundingMode.HALF_UP);
            line.setDiscountAmount(share);
            allocated = allocated.add(share);
        }
        if (largest != null && allocated.compareTo(orderDiscount) != 0) {
            largest.setDiscountAmount(largest.getDiscountAmount().add(orderDiscount.subtract(allocated)));
        }

        for (SalesOrderLine line : lines) {
            BigDecimal lineDiscountShare = line.getDiscountPercent() == null
                    ? BigDecimal.ZERO
                    : line.getUnitPrice()
                            .multiply(BigDecimal.valueOf(line.getQuantity()))
                            .setScale(SCALE, RoundingMode.HALF_UP)
                            .subtract(line.getLineSubtotal());
            // discountAmount = line's own discount + its order-discount allocation
            BigDecimal allocation = line.getDiscountAmount();
            line.setDiscountAmount(lineDiscountShare.add(allocation).setScale(SCALE, RoundingMode.HALF_UP));
            line.setLineTotal(line.getLineSubtotal()
                    .subtract(allocation)
                    .add(line.getTaxAmount())
                    .setScale(SCALE, RoundingMode.HALF_UP));
        }
    }
}
