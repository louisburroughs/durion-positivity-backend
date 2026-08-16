package com.positivity.order.internal.security;

/**
 * Permission constants for the purchase-order aggregate (CAP-320 #1334).
 *
 * <h2>Why these moved and one did not</h2>
 *
 * Permissions follow the aggregate. Creating, revising, approving, cancelling and viewing a
 * purchase order are ordering decisions, so they became {@code order:purchase_order:*} when
 * pos-order took the aggregate. Recording what physically arrived is a different job done by
 * different people, so {@code inventory:purchase_order:receive} stayed where receiving stayed.
 *
 * <p>The {@code inventory:purchase_order:} names these replace are retired rather than kept as
 * aliases: a permission that still grants something no longer here is a grant nobody can reason
 * about.
 */
public final class PurchaseOrderPermissions {

    public static final String PURCHASE_ORDER_VIEW = "order:purchase_order:view";
    public static final String PURCHASE_ORDER_CREATE = "order:purchase_order:create";
    public static final String PURCHASE_ORDER_APPROVE = "order:purchase_order:approve";

    /**
     * Sending an approved order to the vendor.
     *
     * <p>Separate from approval because they are different acts by different people: approving
     * commits the business to the spend, transmitting puts it in front of a supplier who will act
     * on it. A shop may well let a buyer do the first and not the second.
     */
    public static final String PURCHASE_ORDER_TRANSMIT = "order:purchase_order:transmit";

    /**
     * Asking a vendor what it can actually supply against an order's lines.
     *
     * <p>A read, but not a free one: it reaches out to the vendor's own systems, so it is gated
     * separately from viewing the order rather than folded into it.
     */
    public static final String PURCHASE_ORDER_AVAILABILITY_VIEW = "order:purchase_order:availability_view";

    private PurchaseOrderPermissions() {
        // Utility class
    }
}
