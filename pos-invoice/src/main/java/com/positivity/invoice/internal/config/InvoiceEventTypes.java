package com.positivity.invoice.internal.config;

import com.positivity.events.EventTypeRegistration;

import java.util.List;

/**
 * Registry of all event types for the Invoice module.
 * CAP:092 - Preferences & Billing Rules
 * 
 * Event naming convention: BILLING_{RESOURCE}_{ACTION}
 */
public final class InvoiceEventTypes {

    private InvoiceEventTypes() {
        // Utility class
    }

    // ==================== BILLING RULES EVENTS ====================

    /** Get billing rules for a party */
    public static final EventTypeRegistration BILLING_RULES_GET = EventTypeRegistration.fastRead("BILLING_RULES_GET",
            "Retrieve billing rules for a party/customer")
            .apiVersion("1")
            .build();

    /** Upsert billing rules */
    public static final EventTypeRegistration BILLING_RULES_UPSERT = EventTypeRegistration.write("BILLING_RULES_UPSERT",
            "Create or update billing rules for a party/customer")
            .apiVersion("1")
            .build();

    /**
     * Returns all event type registrations for the Invoice module.
     */
    public static List<EventTypeRegistration> all() {
        return List.of(
                BILLING_RULES_GET,
                BILLING_RULES_UPSERT
        );
    }
}
