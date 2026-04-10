package com.positivity.poseventreceiver.internal.config;

import com.positivity.events.EventTypeRegistration;

import java.util.List;

/**
 * Registry of all event types emitted by the pos-event-receiver module.
 * Each event type is registered with appropriate performance thresholds
 * based on expected operation latency characteristics.
 *
 */
public final class EventReceiverEventTypes {

        private EventReceiverEventTypes() {
                // Utility class
        }

        /**
         * All event type registrations for the event-receiver module.
         * Total: 8 event types.
         */
        public static List<EventTypeRegistration> all() {
                return List.of(
                                // EventTypeController - 4 events
                                EventTypeRegistration.write("EVENT_RECEIVER_EVENT_TYPE_CREATE",
                                                "Create a new event type for PreregisteredEvents").build(),
                                EventTypeRegistration.write("EVENT_RECEIVER_EVENT_TYPE_UPSERT",
                                                "Create or update an event type by type code").build(),
                                EventTypeRegistration.write("EVENT_RECEIVER_EVENT_TYPE_UPDATE",
                                                "Update an existing event type").build(),
                                EventTypeRegistration.write("EVENT_RECEIVER_EVENT_TYPE_DELETE",
                                                "Delete an event type").build(),

                                // EmitEventController - 1 event
                                EventTypeRegistration.write("EVENT_RECEIVER_EVENT_RECEIVE",
                                                "Receive and store an emitted event").build(),

                                // EventSummaryController - 3 events
                                EventTypeRegistration.fastRead("EVENT_RECEIVER_SUMMARY_LAST_HOUR",
                                                "Get event summary for the last hour").build(),
                                EventTypeRegistration.fastRead("EVENT_RECEIVER_SUMMARY_LAST_DAY",
                                                "Get event summary for the last day").build(),
                                EventTypeRegistration.fastRead("EVENT_RECEIVER_SUMMARY_LAST_WEEK",
                                                "Get event summary for the last week").build());
        }
}
