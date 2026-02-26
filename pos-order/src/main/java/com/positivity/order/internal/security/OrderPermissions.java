package com.positivity.order.internal.security;

/**
 * Permission constants for core order and order-line operations.
 */
public final class OrderPermissions {

    public static final String ORDER_VIEW = "order:order:view";
    public static final String ORDER_CREATE = "order:order:create";
    public static final String ORDER_EDIT = "order:order:edit";
    public static final String ORDER_CANCEL = "order:order:cancel";

    public static final String ORDER_LINE_VIEW = "order:line:view";
    public static final String ORDER_LINE_CREATE = "order:line:create";
    public static final String ORDER_LINE_EDIT = "order:line:edit";
    public static final String ORDER_LINE_DELETE = "order:line:delete";

    private OrderPermissions() {
        // Utility class
    }
}
