package com.positivity.platformsender.internal.config;

import com.positivity.events.EventTypeRegistration;
import java.util.List;

/** Registry of all event types emitted by the pos-platform-sender module. */
public final class PlatformSenderEventTypes {

    private PlatformSenderEventTypes() {
        // Utility class
    }

    public static List<EventTypeRegistration> all() {
        return List.of(
                // MessageController (FI-2 §1)
                EventTypeRegistration.write("PLATFORM_SENDER_MESSAGE_SEND", "Deliver one rendered email or SMS")
                        .apiVersion("1")
                        .build());
    }
}
