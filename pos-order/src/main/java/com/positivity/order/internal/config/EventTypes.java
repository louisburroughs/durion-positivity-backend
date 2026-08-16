package com.positivity.order.internal.config;

import com.positivity.events.EventTypeRegistration;
import java.util.List;

/**
 * Registry of all event types for the Order module.
 *
 * Event naming convention: ORDER_{RESOURCE}_{ACTION}
 */
public final class EventTypes {

    private EventTypes() {
        // Utility class
    }

    // ==================== PRICE OVERRIDE EVENTS ====================

    /** Price override applied to an order line */
    public static final EventTypeRegistration ORDER_PRICE_OVERRIDE_APPLY = EventTypeRegistration.write(
                    "ORDER_PRICE_OVERRIDE_APPLY", "Price override applied to an order line")
            .apiVersion("1")
            .build();

    /** Price override approved by authorized user */
    public static final EventTypeRegistration ORDER_PRICE_OVERRIDE_APPROVE = EventTypeRegistration.approval(
                    "ORDER_PRICE_OVERRIDE_APPROVE", "Price override approved by authorized user")
            .apiVersion("1")
            .build();

    /** Price override rejected by authorized user */
    public static final EventTypeRegistration ORDER_PRICE_OVERRIDE_REJECT = EventTypeRegistration.approval(
                    "ORDER_PRICE_OVERRIDE_REJECT", "Price override rejected by authorized user")
            .apiVersion("1")
            .build();

    /** Search for price overrides by criteria */
    public static final EventTypeRegistration ORDER_PRICE_OVERRIDE_SEARCH = EventTypeRegistration.search(
                    "ORDER_PRICE_OVERRIDE_SEARCH", "Search for price overrides by order ID, status, or date range")
            .apiVersion("1")
            .build();

    /** List pending price override approvals */
    public static final EventTypeRegistration ORDER_PRICE_OVERRIDE_LIST_PENDING = EventTypeRegistration.search(
                    "ORDER_PRICE_OVERRIDE_LIST_PENDING", "List all pending price override approvals")
            .apiVersion("1")
            .build();

    public static final EventTypeRegistration ORDER_CART_CANCEL_REQUEST = EventTypeRegistration.write(
                    "ORDER_CART_CANCEL_REQUEST", "Cancellation requested for a cart order")
            .apiVersion("1")
            .build();

    public static final EventTypeRegistration ORDER_CART_CANCEL_RETRY = EventTypeRegistration.write(
                    "ORDER_CART_CANCEL_RETRY", "Retry of a failed cancellation billing step")
            .apiVersion("1")
            .build();

    // ==================== ALL EVENT TYPES ====================

    /** All event types for registration at startup */
    public static final List<EventTypeRegistration> ALL_EVENT_TYPES = List.of(
            ORDER_PRICE_OVERRIDE_APPLY,
            ORDER_PRICE_OVERRIDE_APPROVE,
            ORDER_PRICE_OVERRIDE_REJECT,
            ORDER_PRICE_OVERRIDE_SEARCH,
            ORDER_PRICE_OVERRIDE_LIST_PENDING,
            ORDER_CART_CANCEL_REQUEST,
            ORDER_CART_CANCEL_RETRY,
            EventTypeRegistration.write("ORDER_CART_CREATE", "Sales order cart created")
                    .build(),
            EventTypeRegistration.write("ORDER_CART_ITEM_ADD", "Item added to sales order cart")
                    .build(),
            EventTypeRegistration.write("ORDER_CART_ITEM_UPDATE", "Sales order cart item quantity updated")
                    .build(),
            EventTypeRegistration.write("ORDER_CART_ITEM_REMOVE", "Item removed from sales order cart")
                    .build(),
            EventTypeRegistration.write("ORDER_LINK_SOURCE", "Source linked to sales order")
                    .build(),
            EventTypeRegistration.search("ORDER_CART_LIST", "List sales order carts for parking/resume")
                    .build(),
            EventTypeRegistration.write("ORDER_CART_QUOTE", "Cart converted to a counter quote")
                    .build(),
            EventTypeRegistration.write("ORDER_CART_QUOTE_REOPEN", "Counter quote reopened for editing")
                    .build(),
            EventTypeRegistration.write("ORDER_CART_DISCOUNT_APPLY", "Order-level discount applied")
                    .build(),
            EventTypeRegistration.write("ORDER_CART_DISCOUNT_REMOVE", "Order-level discount removed")
                    .build(),
            EventTypeRegistration.write("ORDER_CHECKOUT", "Order checked out to PENDING_PAYMENT with invoice created")
                    .build(),
            EventTypeRegistration.write("ORDER_VOID", "Unsettled order voided with its invoice cancelled")
                    .build(),
            EventTypeRegistration.write("ORDER_SESSION_OPEN", "Register session opened for a terminal")
                    .apiVersion("1")
                    .build(),
            EventTypeRegistration.write("ORDER_SESSION_CASH_MOVEMENT", "Drawer cash paid-in/paid-out recorded")
                    .apiVersion("1")
                    .build(),
            EventTypeRegistration.write("ORDER_SESSION_BEGIN_CLOSE", "Register session close begun with counted cash")
                    .apiVersion("1")
                    .build(),
            EventTypeRegistration.write("ORDER_SESSION_CONFIRM_CLOSE", "Register session closed and drawer reconciled")
                    .apiVersion("1")
                    .build(),
            EventTypeRegistration.write("ORDER_RETURN_CREATE", "Return created against a completed order")
                    .apiVersion("1")
                    .build(),
            EventTypeRegistration.fastRead("ORDER_RETURN_GET", "Return read by id")
                    .apiVersion("1")
                    .build(),
            EventTypeRegistration.search("ORDER_RETURN_LIST", "Returns listed for an original order")
                    .apiVersion("1")
                    .build(),
            EventTypeRegistration.fastRead(
                            "ORDER_RETURN_RETURNABLE", "Per-line returnable quantities read for a completed order")
                    .apiVersion("1")
                    .build(),
            EventTypeRegistration.approval("ORDER_RETURN_APPROVE", "Return above threshold approved")
                    .apiVersion("1")
                    .build(),
            EventTypeRegistration.approval("ORDER_RETURN_REJECT", "Return above threshold rejected")
                    .apiVersion("1")
                    .build(),
            EventTypeRegistration.write("ORDER_RETURN_PROCESS", "Return orchestration saga run (refund + restock)")
                    .apiVersion("1")
                    .build(),
            EventTypeRegistration.write("ORDER_RETURN_RETRY", "Return saga retried after a refund failure")
                    .apiVersion("1")
                    .build(),

            // PurchaseOrderController — the aggregate moved here from pos-inventory (CAP-320 #1334)
            EventTypeRegistration.write("ORDER_PURCHASE_ORDER_CREATE", "Create a purchase order")
                    .apiVersion("1")
                    .build(),
            EventTypeRegistration.fastRead("ORDER_PURCHASE_ORDER_GET", "Get purchase order")
                    .apiVersion("1")
                    .build(),
            EventTypeRegistration.search("ORDER_PURCHASE_ORDER_LIST", "List purchase orders")
                    .apiVersion("1")
                    .build(),
            EventTypeRegistration.approval("ORDER_PURCHASE_ORDER_APPROVE", "Approve a purchase order")
                    .apiVersion("1")
                    .build(),
            EventTypeRegistration.write("ORDER_PURCHASE_ORDER_REVISE", "Revise a purchase order")
                    .apiVersion("1")
                    .build(),
            EventTypeRegistration.write("ORDER_PURCHASE_ORDER_CANCEL", "Cancel a purchase order")
                    .apiVersion("1")
                    .build(),
            EventTypeRegistration.write(
                            "ORDER_PURCHASE_ORDER_TRANSMIT", "Send an approved purchase order to its vendor")
                    .apiVersion("1")
                    .build(),
            EventTypeRegistration.fastRead(
                            "ORDER_PURCHASE_ORDER_AVAILABILITY",
                            "Read live vendor availability for a purchase order's lines")
                    .apiVersion("1")
                    .build());
}
