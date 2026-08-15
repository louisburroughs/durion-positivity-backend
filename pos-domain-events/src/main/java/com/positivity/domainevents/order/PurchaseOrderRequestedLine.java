package com.positivity.domainevents.order;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One line of a {@link PurchaseOrderRequestedV1}.
 *
 * <p>{@code quantity} is in the product's base unit. A line ordered in a vendor pack instead
 * carries {@code documentUom}/{@code documentQuantity} and leaves {@code quantity} null, because
 * what the order should record is what was actually ordered — three cases, not thirty-six units —
 * and the conversion is the order's to make and to keep an audit of.
 *
 * @param lineNumber     position on the order
 * @param skuId          catalog product ordered
 * @param description    line description as it should appear on the order
 * @param quantity       how much to order, in the product's base unit
 * @param unitCostMinor  expected cost per unit in minor units
 * @param documentUom      unit the line was ordered in, when it differed from base — a vendor
 *                         pack, typically. pos-order converts it, because the order is what
 *                         records what was actually ordered and in what unit
 * @param documentQuantity how many {@code documentUom} units were ordered
 * @param taxCodeId      tax code to apply, when one is known
 * @param glAccountId    GL account to charge, when one is known
 */
public record PurchaseOrderRequestedLine(
        int lineNumber,
        @Nullable UUID skuId,
        @NonNull String description,
        @Nullable BigDecimal quantity,
        long unitCostMinor,
        @Nullable String documentUom,
        @Nullable BigDecimal documentQuantity,
        @Nullable String taxCodeId,
        @Nullable String glAccountId) {

    public PurchaseOrderRequestedLine {
        Objects.requireNonNull(description, "description must not be null");
    }
}
