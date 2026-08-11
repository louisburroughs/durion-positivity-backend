package com.positivity.marketing.internal.config;

import com.positivity.events.EventTypeRegistration;
import java.util.List;

/** Registry of all event types emitted by the pos-marketing module (Story #1145). */
public final class MarketingEventTypes {

    private MarketingEventTypes() {
        // Utility class
    }

    public static List<EventTypeRegistration> all() {
        return List.of(
                // CampaignController (Story #1146)
                EventTypeRegistration.fastRead("MARKETING_CAMPAIGN_LIST", "List marketing campaigns")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.fastRead("MARKETING_CAMPAIGN_GET", "Retrieve a campaign")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("MARKETING_CAMPAIGN_CREATE", "Create a marketing campaign")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("MARKETING_CAMPAIGN_UPDATE", "Update a draft campaign")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("MARKETING_CAMPAIGN_SCHEDULE", "Schedule a campaign for delivery")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("MARKETING_CAMPAIGN_PAUSE", "Pause a scheduled or sending campaign")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("MARKETING_CAMPAIGN_RESUME", "Resume a paused campaign")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("MARKETING_CAMPAIGN_CANCEL", "Cancel a campaign")
                        .apiVersion("1")
                        .build(),

                // MessageTemplateController (Story #1147)
                EventTypeRegistration.fastRead("MARKETING_TEMPLATE_LIST", "List message templates")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.fastRead("MARKETING_TEMPLATE_GET", "Retrieve a message template")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("MARKETING_TEMPLATE_CREATE", "Create a message template")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("MARKETING_TEMPLATE_UPDATE", "Update a message template")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.write("MARKETING_TEMPLATE_DELETE", "Delete a message template")
                        .apiVersion("1")
                        .build(),

                // Audience binding and preview (Story #1148)
                EventTypeRegistration.search(
                                "MARKETING_AUDIENCE_PREVIEW",
                                "Preview a campaign audience with consent and suppression applied")
                        .apiVersion("1")
                        .build(),

                // Send orchestration (Story #1149)
                EventTypeRegistration.write("MARKETING_CAMPAIGN_SEND", "Dispatch a campaign to its audience")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.fastRead("MARKETING_CAMPAIGN_SENDS_LIST", "List per-recipient send records")
                        .apiVersion("1")
                        .build(),

                // Analytics (Story #1152)
                EventTypeRegistration.search(
                                "MARKETING_CAMPAIGN_STATS_GET", "Retrieve a campaign's reach and attribution funnel")
                        .apiVersion("1")
                        .build(),
                EventTypeRegistration.search(
                                "MARKETING_PROGRAM_STATS_GET",
                                "Compare commercial and individual arms of a campaign program")
                        .apiVersion("1")
                        .build());
    }
}
