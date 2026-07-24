package com.positivity.order.internal.security;

/**
 * Permission constants for core order and order-line operations.
 */
public final class OrderPermissions {

    public static final String ORDER_VIEW = "order:order:view";
    public static final String ORDER_CREATE = "order:order:create";
    public static final String ORDER_EDIT = "order:order:edit";
    public static final String ORDER_CANCEL = "order:order:cancel";

    public static final String ORDER_DISCOUNT = "order:order:discount";
    public static final String ORDER_QUOTE = "order:order:quote";
    public static final String ORDER_CHECKOUT = "order:order:checkout";
    public static final String ORDER_VOID = "order:order:void";
    public static final String ORDER_CHARGE_ON_ACCOUNT = "order:order:charge_on_account";

    public static final String ORDER_LINE_VIEW = "order:line:view";
    public static final String ORDER_LINE_CREATE = "order:line:create";
    public static final String ORDER_LINE_EDIT = "order:line:edit";
    public static final String ORDER_LINE_DELETE = "order:line:delete";

    // Register sessions & cash management (odoo-parity G1/G2)
    public static final String ORDER_SESSION_OPEN = "order:session:open";
    public static final String ORDER_SESSION_VIEW = "order:session:view";
    public static final String ORDER_SESSION_CASH_MOVEMENT = "order:session:cash_movement";
    public static final String ORDER_SESSION_CLOSE = "order:session:close";
    public static final String ORDER_SESSION_APPROVE_VARIANCE = "order:session:approve_variance";

    // Returns & refunds (odoo-parity F1/F2)
    public static final String ORDER_RETURN_CREATE = "order:return:create";
    public static final String ORDER_RETURN_APPROVE = "order:return:approve";
    public static final String ORDER_RETURN_VIEW = "order:return:view";

    private OrderPermissions() {
        // Utility class
    }
}
