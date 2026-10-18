package com.positivity.inboundmail.internal.config;

import com.positivity.events.EventTypeRegistration;
import java.util.List;

/** Registry of all event types emitted by the pos-inbound-mail module. */
public final class InboundMailEventTypes {

    private InboundMailEventTypes() {
        // Utility class
    }

    public static List<EventTypeRegistration> all() {
        return List.of(
                // AddressController (S27)
                EventTypeRegistration.write(
                                "INBOUND_MAIL_ADDRESS_ISSUE", "Return or issue the tenant's inbound bill address")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write(
                                "INBOUND_MAIL_ADDRESS_ROTATE", "Retire the tenant's inbound bill address and issue a new one")
                        .apiVersion("1")
                        .build(),
                // StagedAttachmentController (S27)
                EventTypeRegistration.fastRead(
                                "INBOUND_MAIL_STAGED_ATTACHMENT_GET", "Fetch one staged email attachment")
                        .apiVersion("1")
                        .build());
    }
}
