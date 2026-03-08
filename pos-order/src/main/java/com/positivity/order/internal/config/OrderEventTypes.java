package com.positivity.order.internal.config;

import com.positivity.events.EventTypeRegistration;

import java.util.List;

/**
 * Registry of all event types for the Order module.
 * 
 * Event naming convention: ORDER_{RESOURCE}_{ACTION}
 */
public final class OrderEventTypes {

        private OrderEventTypes() {
                // Utility class
        }

        // ==================== PRICE OVERRIDE EVENTS ====================

        /** Price override applied to an order line */
        public static final EventTypeRegistration ORDER_PRICE_OVERRIDE_APPLY = EventTypeRegistration
                        .write("ORDER_PRICE_OVERRIDE_APPLY",
                                        "Price override applied to an order line")
                        .apiVersion("1")
                        .build();

        /** Price override approved by authorized user */
        public static final EventTypeRegistration ORDER_PRICE_OVERRIDE_APPROVE = EventTypeRegistration
                        .approval("ORDER_PRICE_OVERRIDE_APPROVE",
                                        "Price override approved by authorized user")
                        .apiVersion("1")
                        .build();

        /** Price override rejected by authorized user */
        public static final EventTypeRegistration ORDER_PRICE_OVERRIDE_REJECT = EventTypeRegistration
                        .approval("ORDER_PRICE_OVERRIDE_REJECT",
                                        "Price override rejected by authorized user")
                        .apiVersion("1")
                        .build();

        /** Search for price overrides by criteria */
        public static final EventTypeRegistration ORDER_PRICE_OVERRIDE_SEARCH = EventTypeRegistration
                        .search("ORDER_PRICE_OVERRIDE_SEARCH",
                                        "Search for price overrides by order ID, status, or date range")
                        .apiVersion("1")
                        .build();

        /** List pending price override approvals */
        public static final EventTypeRegistration ORDER_PRICE_OVERRIDE_LIST_PENDING = EventTypeRegistration
                        .search("ORDER_PRICE_OVERRIDE_LIST_PENDING",
                                        "List all pending price override approvals")
                        .apiVersion("1")
                        .build();

        public static final EventTypeRegistration ORDER_CART_CANCEL_REQUEST = EventTypeRegistration
                        .write("ORDER_CART_CANCEL_REQUEST", "Cancellation requested for a cart order")
                        .apiVersion("1")
                        .build();

        public static final EventTypeRegistration ORDER_CART_CANCEL_RETRY = EventTypeRegistration
                        .write("ORDER_CART_CANCEL_RETRY", "Retry of a failed cancellation billing step")
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
                        EventTypeRegistration.write("ORDER_CART_CREATE", "Sales order cart created").build(),
                        EventTypeRegistration.write("ORDER_CART_ITEM_ADD", "Item added to sales order cart").build(),
                        EventTypeRegistration.write("ORDER_CART_ITEM_UPDATE", "Sales order cart item quantity updated")
                                        .build(),
                        EventTypeRegistration.write("ORDER_CART_ITEM_REMOVE", "Item removed from sales order cart")
                                        .build(),
                        EventTypeRegistration.write("ORDER_LINK_SOURCE", "Source linked to sales order").build());
}
