package com.positivity.customer.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.customer.internal.entity.CustomerInteraction;
import com.positivity.customer.internal.entity.ProcessedEvent;
import com.positivity.customer.internal.enums.InteractionDirection;
import com.positivity.customer.internal.enums.InteractionType;
import com.positivity.customer.internal.enums.MarketingChannel;
import com.positivity.customer.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link MarketingEventsListener}.
 *
 * <p>
 * Campaign sends become customer-interaction rows so a service advisor can see
 * what marketing has already sent a customer. Delivery is at-least-once, so the
 * listener is idempotent twice over: the {@code processed_events} log skips a
 * replayed envelope, and the interaction's unique {@code source_event_id} index
 * is the backstop if the log and the write ever diverge.
 *
 * <p>
 * The partial-data handling is the subtle part and is asserted directly. A send
 * with no recipient is dropped, because an interaction not attached to a party
 * is unreachable. But a send whose channel this module does not model is still
 * recorded without a channel — the touch happened, and dropping it would hide a
 * real contact from the advisor.
 */
@DisplayName("MarketingEventsListener — campaign sends into customer interactions")
class MarketingEventsListenerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC);
    private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");
    private static final String EVENT_ID = "01960000-0000-7000-8000-000000000001";
    private static final UUID PARTY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID CONTACT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID CAMPAIGN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final CustomerInteractionServiceImpl interactionService = mock(CustomerInteractionServiceImpl.class);

    private MarketingEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new MarketingEventsListener(TEST_CLOCK, new ObjectMapper(), processedEvents, interactionService);
    }

    private static String campaignSent(String channel, String recipientPartyId) {
        return """
                {"eventId":"%s","eventType":"marketing.campaign.sent",
                 "payload":{"recipientPartyId":%s,"contactId":"%s","campaignId":"%s",
                  "campaignCode":"SPRING24","channel":%s,"subject":"Spring service offer"}}
                """.formatted(
                        EVENT_ID,
                        recipientPartyId == null ? "null" : "\"" + recipientPartyId + "\"",
                        CONTACT_ID,
                        CAMPAIGN_ID,
                        channel == null ? "null" : "\"" + channel + "\"");
    }

    private CustomerInteraction capturedInteraction() {
        ArgumentCaptor<CustomerInteraction> captor = ArgumentCaptor.captor();
        verify(interactionService).ingest(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("records a campaign send as an outbound CAMPAIGN_SEND interaction")
    void campaignSent_recordsInteraction() {
        listener.onMarketingEvent(campaignSent("EMAIL", PARTY_ID.toString()));

        CustomerInteraction interaction = capturedInteraction();
        assertThat(interaction.getPartyId()).isEqualTo(PARTY_ID);
        assertThat(interaction.getContactId()).isEqualTo(CONTACT_ID);
        assertThat(interaction.getCampaignId()).isEqualTo(CAMPAIGN_ID);
        assertThat(interaction.getCampaignCode()).isEqualTo("SPRING24");
        assertThat(interaction.getType()).isEqualTo(InteractionType.CAMPAIGN_SEND);
        assertThat(interaction.getDirection()).isEqualTo(InteractionDirection.OUTBOUND);
        assertThat(interaction.getChannel()).isEqualTo(MarketingChannel.EMAIL);
        assertThat(interaction.getSubject()).isEqualTo("Spring service offer");
        assertThat(interaction.getSummary()).isEqualTo("Campaign send: SPRING24");
        assertThat(interaction.getSourceEventId()).isEqualTo(EVENT_ID);
        assertThat(interaction.getOccurredAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("records the event as processed, owned by pos-marketing")
    void campaignSent_recordsProcessedEvent() {
        listener.onMarketingEvent(campaignSent("EMAIL", PARTY_ID.toString()));

        ArgumentCaptor<ProcessedEvent> saved = ArgumentCaptor.captor();
        verify(processedEvents).save(saved.capture());
        assertThat(saved.getValue().getEventId()).isEqualTo(EVENT_ID);
        assertThat(saved.getValue().getOwner()).isEqualTo("pos-marketing");
        assertThat(saved.getValue().getProcessedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("ignores an event type this listener does not handle")
    void otherEventType_isIgnored() {
        String message = "{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"marketing.campaign.created\","
                + "\"payload\":{\"recipientPartyId\":\"" + PARTY_ID + "\"}}";

        listener.onMarketingEvent(message);

        verify(interactionService, never()).ingest(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("skips an event already recorded in processed_events")
    void duplicateEvent_isSkipped() {
        when(processedEvents.existsById(EVENT_ID)).thenReturn(true);

        listener.onMarketingEvent(campaignSent("EMAIL", PARTY_ID.toString()));

        verify(interactionService, never()).ingest(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("skips an envelope with no eventId, since idempotency cannot be tracked without one")
    void missingEventId_isSkipped() {
        String message =
                "{\"eventType\":\"marketing.campaign.sent\",\"payload\":{\"recipientPartyId\":\"" + PARTY_ID + "\"}}";

        listener.onMarketingEvent(message);

        verify(interactionService, never()).ingest(any());
    }

    @Test
    @DisplayName("drops a send with no recipient — an interaction with no party is unreachable")
    void missingRecipient_isDropped() {
        listener.onMarketingEvent(campaignSent("EMAIL", null));

        verify(interactionService, never()).ingest(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("drops a send whose recipient id is not a UUID")
    void malformedRecipient_isDropped() {
        listener.onMarketingEvent(campaignSent("EMAIL", "not-a-uuid"));

        verify(interactionService, never()).ingest(any());
    }

    @Test
    @DisplayName("records a send on an unmodelled channel without one, rather than dropping the touch")
    void unknownChannel_isRecordedWithoutChannel() {
        listener.onMarketingEvent(campaignSent("CARRIER_PIGEON", PARTY_ID.toString()));

        // The send happened; dropping it would hide a real customer contact.
        assertThat(capturedInteraction().getChannel()).isNull();
    }

    @Test
    @DisplayName("records a send with no channel at all")
    void missingChannel_isRecordedWithoutChannel() {
        listener.onMarketingEvent(campaignSent(null, PARTY_ID.toString()));

        assertThat(capturedInteraction().getChannel()).isNull();
    }

    @Test
    @DisplayName("falls back to a generic summary when the send carries no campaign code")
    void missingCampaignCode_usesGenericSummary() {
        String message = """
                {"eventId":"%s","eventType":"marketing.campaign.sent",
                 "payload":{"recipientPartyId":"%s","channel":"SMS"}}
                """.formatted(EVENT_ID, PARTY_ID);

        listener.onMarketingEvent(message);

        CustomerInteraction interaction = capturedInteraction();
        assertThat(interaction.getSummary()).isEqualTo("Campaign send");
        assertThat(interaction.getCampaignCode()).isNull();
        assertThat(interaction.getContactId()).isNull();
        assertThat(interaction.getCampaignId()).isNull();
    }

    @Test
    @DisplayName("still records the event when the interaction was already written by its unique index")
    void whenInteractionDeduplicated_stillRecordsProcessedEvent() {
        // ingest() reports false when the source_event_id index rejected a duplicate;
        // the event is still fully handled and must not be reprocessed.
        when(interactionService.ingest(any())).thenReturn(false);

        listener.onMarketingEvent(campaignSent("EMAIL", PARTY_ID.toString()));

        verify(processedEvents).save(any(ProcessedEvent.class));
    }
}
