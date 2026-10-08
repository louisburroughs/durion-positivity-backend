package com.positivity.domainevents.inventory;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One line of a {@link GoodsReceiptRecordedV1}: how much of one purchase-order line arrived, what the
 * order accrues for it and what inventory booked it at.
 *
 * <p>{@code quantityReceived} is in the product's base unit, already converted from whatever unit
 * the receiver keyed. Publishing the keyed quantity instead would make every consumer repeat the
 * conversion, and a consumer that got it wrong would silently disagree with the ledger.
 *
 * <h2>Accrued value and inventory value (CAP:550 S41 #2602, #2598)</h2>
 *
 * Both amounts are whole minor units of the fact's {@code currencyCode}, each rounded HALF_UP on its
 * own line (ADR-0067 OP-11, PC-6). {@code accruedAmountMinor} is what the order owes for the line: the
 * quantity at the purchase-order line's price, 0 when no priced line claims it.
 * {@code inventoryValueMinor} is what inventory booked: {@code quantityReceived} × the unit cost the
 * costing engine stamped on the line's {@code GOODS_RECEIPT} ledger row. They differ under
 * {@code STANDARD} cost, for an unpriced line (accrual 0, value at the running average) and by
 * pack-price rounding.
 *
 * <p>{@code inventoryValueMinor} is null when the ledger row is uncosted ({@code costSource NONE};
 * for an order in the functional currency the accrual is then 0) or when the order is not in the
 * functional currency (ADR-0067 DF-6: the row takes no document cost from it). For an order in the
 * functional currency a null value therefore always sits beside a zero accrual.
 *
 * <p>The fields after {@code accruedAmountMinor} were added within v1 (ADR-0044 §3) and are boxed:
 * a fact published before they existed reads them as null. pos-inventory always states
 * {@code receiptLineId}, {@code costSource} and {@code ledgerEntryId} now.
 *
 * @param poLineId            the purchase-order line received against; null when the receipt could
 *                            not be attributed to a specific line, in which case the order can deduct
 *                            the value but not the quantity
 * @param sku                 stock reference as recorded on the receipt
 * @param quantityReceived    how much arrived, in the product's base unit
 * @param accruedAmountMinor  what the order accrues for this line, in minor units of the fact's
 *                            {@code currencyCode}
 * @param receiptLineId       the goods-receipt line ({@code goods_receipt_line.receipt_line_id})
 * @param productId           the product the {@code GOODS_RECEIPT} ledger row posted against; null
 *                            when the line names neither a purchase-order line with a product nor a
 *                            UUID sku
 * @param inventoryValueMinor what inventory booked the line at, in minor units of the fact's
 *                            {@code currencyCode}; null when uncosted or the order is not in the
 *                            functional currency
 * @param costSource          {@code STANDARD}, {@code AVERAGE} or {@code NONE} (uncosted), as on the
 *                            other inventory posting facts
 * @param ledgerEntryId       the line's {@code GOODS_RECEIPT} ledger row; on a cross-dock never the
 *                            paired {@code GOODS_ISSUE}
 */
public record GoodsReceiptLine(
        @Nullable UUID poLineId,
        @Nullable String sku,
        @NonNull BigDecimal quantityReceived,
        long accruedAmountMinor,
        @Nullable UUID receiptLineId,
        @Nullable UUID productId,
        @Nullable Long inventoryValueMinor,
        @Nullable String costSource,
        @Nullable UUID ledgerEntryId) {

    public GoodsReceiptLine {
        Objects.requireNonNull(quantityReceived, "quantityReceived must not be null");
    }
}
