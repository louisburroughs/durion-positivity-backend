package com.positivity.domainevents.inventory;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One line of a {@link GoodsReceiptRecordedV1}: how much of one purchase-order line arrived.
 *
 * <p>{@code quantityReceived} is in the product's base unit, already converted from whatever unit
 * the receiver keyed. Publishing the keyed quantity instead would make every consumer repeat the
 * conversion, and a consumer that got it wrong would silently disagree with the ledger.
 *
 * @param poLineId         the purchase-order line received against; null when the receipt could not
 *                         be attributed to a specific line, in which case the order can deduct the
 *                         value but not the quantity
 * @param sku              stock reference as recorded on the receipt
 * @param quantityReceived how much arrived, in the product's base unit
 * @param accruedAmountMinor value of this line in minor units
 */
public record GoodsReceiptLine(
        @Nullable UUID poLineId,
        @Nullable String sku,
        @NonNull BigDecimal quantityReceived,
        long accruedAmountMinor) {

    public GoodsReceiptLine {
        Objects.requireNonNull(quantityReceived, "quantityReceived must not be null");
    }
}
