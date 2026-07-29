package com.positivity.customer.internal.service;

import com.positivity.customer.internal.entity.CustomerInteraction;
import com.positivity.customer.internal.entity.ProcessedEvent;
import com.positivity.customer.internal.enums.InteractionDirection;
import com.positivity.customer.internal.enums.InteractionType;
import com.positivity.customer.internal.enums.MarketingChannel;
import com.positivity.customer.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.marketing.MarketingCampaignSentV1;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code marketing.events.v1} and writes campaign sends onto the recipient's
 * interaction timeline (Story #1151).
 *
 * <p>This is what closes the loop for a CSR: without it, "did we email this customer about the
 * spring promotion?" is answerable only by someone with access to the marketing module. With
 * it, the send appears in the same party history as call notes and workorder notes.
 *
 * <p>Delivery is at-least-once, so ingestion is idempotent twice over — the
 * {@code processed_events} log skips replayed envelopes, and the interaction's unique
 * {@code source_event_id} index is the backstop if the log and the write ever diverge.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.customer.kafka", name = "enabled", havingValue = "true")
public class MarketingEventsListener {

    private static final String OWNER = "pos-marketing";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final CustomerInteractionServiceImpl interactionService;

    public MarketingEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            CustomerInteractionServiceImpl interactionService) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.interactionService = interactionService;
    }

    @KafkaListener(
            topics = "${pos.customer.kafka.marketing-events-topic:marketing.events.v1}",
            groupId = "${pos.customer.kafka.marketing-events-consumer-group:pos-customer-marketing-events}")
    @Transactional
    public void onMarketingEvent(@NonNull String message) {
        JsonNode envelope = objectMapper.readTree(message);
        String eventId = envelope.path("eventId").asString("");
        String eventType = envelope.path("eventType").asString("");
        if (!MarketingCampaignSentV1.EVENT_TYPE.equals(eventType)) {
            return;
        }
        if (eventId.isBlank() || processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate or unidentified marketing event id={}", eventId);
            return;
        }

        JsonNode payload = envelope.path("payload");
        UUID partyId = optionalUuid(payload, "recipientPartyId");
        if (partyId == null) {
            log.warn("marketing.campaign.sent missing recipientPartyId; ignoring event {}", eventId);
            return;
        }

        String campaignCode = payload.path("campaignCode").asString(null);
        boolean written = interactionService.ingest(CustomerInteraction.builder()
                .partyId(partyId)
                .contactId(optionalUuid(payload, "contactId"))
                .type(InteractionType.CAMPAIGN_SEND)
                .channel(channelOrNull(payload.path("channel").asString(null)))
                .direction(InteractionDirection.OUTBOUND)
                .campaignId(optionalUuid(payload, "campaignId"))
                .campaignCode(campaignCode)
                .subject(payload.path("subject").asString(null))
                .summary(campaignCode != null ? "Campaign send: " + campaignCode : "Campaign send")
                .actor(OWNER)
                .sourceEventId(eventId)
                .occurredAt(Instant.now(clock))
                .build());

        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
        if (written) {
            log.debug("Recorded campaign-send interaction for party {} from event {}", partyId, eventId);
        }
    }

    private static @Nullable MarketingChannel channelOrNull(@Nullable String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        try {
            return MarketingChannel.valueOf(channel);
        } catch (IllegalArgumentException ex) {
            // A channel this module does not model is still a real send; log the touch without it
            // rather than dropping the whole interaction.
            log.warn("Unknown marketing channel '{}' on campaign-send fact", channel);
            return null;
        }
    }

    private static @Nullable UUID optionalUuid(JsonNode payload, String field) {
        String value = payload.path(field).asString(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
