package com.positivity.poseventreceiver.internal.config;

import com.positivity.events.EventTypeRegistration;

import java.util.List;

/**
 * Registry of all event types emitted by the pos-event-receiver module.
 * Each event type is registered with appropriate performance thresholds
 * based on expected operation latency characteristics.
 *
 * <p>
 * Note: The upsert endpoint (PUT /v1/eventTypes/code/{typeCode}) is
 * intentionally
 * excluded to avoid recursive event emission, as that endpoint is used by other
 * modules to register their event types.
 * </p>
 */
public final class EventReceiverEventTypes {

    private EventReceiverEventTypes() {
        // Utility class
    }

    /**
     * All event type registrations for the event-receiver module.
     * Total: 3 event types.
     */
    public static List<EventTypeRegistration> all() {
        return List.of(
                // EventTypeController - 2 events (excluding upsert to avoid recursion)
                EventTypeRegistration.write("EVENT_RECEIVER_EVENT_TYPE_CREATE",
                        "Create a new event type for PreregisteredEvents").build(),
                EventTypeRegistration.write("EVENT_RECEIVER_EVENT_TYPE_UPDATE",
                        "Update an existing event type").build(),

                // EmitEventController - 1 event
                EventTypeRegistration.write("EVENT_RECEIVER_EVENT_RECEIVE",
                        "Receive and store an emitted event").build());
    }
}
