package com.positivity.tax.internal.config;

import com.positivity.events.EventTypeRegistration;
import java.util.List;

/**
 * Event type registry for pos-tax module.
 * <p>
 * Defines all event types emitted by tax service endpoints with their
 * performance thresholds.
 */
public final class EventTypes {

    private EventTypes() {
        // Utility class
    }

    /**
     * Returns all event type registrations for the tax service.
     *
     * @return list of event type registrations
     */
    public static List<EventTypeRegistration> all() {
        return List.of(
                EventTypeRegistration.write("TAX_CALCULATE", "Calculate tax for line items")
                        .description(
                                "Calculates tax based on line items and location. Routes to external service in production or test calculator in test mode.")
                        .build(),
                EventTypeRegistration.approval("TAX_EXEMPTION_CERT_CREATE", "Create tax exemption certificate")
                        .description(
                                "Registers a customer tax exemption certificate in the pos-tax exemption registry.")
                        .build(),
                EventTypeRegistration.approval("TAX_EXEMPTION_CERT_UPDATE", "Update tax exemption certificate")
                        .description("Updates a customer tax exemption certificate in the pos-tax exemption registry.")
                        .build());
    }
}
