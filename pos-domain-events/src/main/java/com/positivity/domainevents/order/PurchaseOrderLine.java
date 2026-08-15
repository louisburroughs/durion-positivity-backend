package com.positivity.domainevents.order;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One line of a purchase order, as carried by {@link PurchaseOrderUpdatedV1}.
 *
 * <p>Two quantities, and the difference between them is the contract. {@code orderedQuantity} is
 * what was ordered and does not move as goods arrive; {@code openQuantity} is what is still
 * outstanding and falls to zero as the line is received. Availability-to-promise sums the
 * <em>open</em> figure, because stock already in the building is counted by the ledger and adding
 * the ordered figure would count it twice.
 *
 * @param lineId           identity of the line within the order
 * @param lineNumber       1-based position, for citing a line back to a vendor
 * @param skuId            the stock item ordered; the key every consumer joins on
 * @param orderedQuantity  quantity ordered; fixed once the order is approved
 * @param openQuantity     quantity still outstanding; zero once the line is fully received
 * @param unitCostMinor    unit cost in minor units of the order's currency, when priced
 * @param description      line description as ordered, for display
 */
public record PurchaseOrderLine(
        @NonNull UUID lineId,
        int lineNumber,
        @NonNull UUID skuId,
        @NonNull BigDecimal orderedQuantity,
        @NonNull BigDecimal openQuantity,
        @Nullable Long unitCostMinor,
        @Nullable String description) {

    public PurchaseOrderLine {
        Objects.requireNonNull(lineId, "lineId must not be null");
        Objects.requireNonNull(skuId, "skuId must not be null");
        Objects.requireNonNull(orderedQuantity, "orderedQuantity must not be null");
        Objects.requireNonNull(openQuantity, "openQuantity must not be null");
    }

    /** Whether anything on this line is still expected to arrive. */
    public boolean hasOutstandingSupply() {
        return openQuantity.signum() > 0;
    }
}
