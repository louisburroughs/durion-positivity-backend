package com.positivity.marketing.internal.service;

import com.positivity.domainevents.customer.CustomerRedemptionRecordedV1;
import com.positivity.domainevents.customer.CustomerSuppressionChangedV1;
import com.positivity.marketing.internal.entity.CampaignAttribution;
import com.positivity.marketing.internal.entity.ProcessedEvent;
import com.positivity.marketing.internal.entity.SuppressionReplica;
import com.positivity.marketing.internal.repository.CampaignAttributionRepository;
import com.positivity.marketing.internal.repository.CampaignRepository;
import com.positivity.marketing.internal.repository.ProcessedEventRepository;
import com.positivity.marketing.internal.repository.SuppressionReplicaRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code customer.events.v1} (Stories #1140/#1152).
 *
 * <p>Two facts matter here:
 *
 * <ul>
 *   <li>{@code customer.suppression.changed} maintains the local {@code ext_suppression}
 *       replica, so the send worker's per-recipient check is a local read rather than a
 *       network call inside a bulk send.
 *   <li>{@code customer.redemption.recorded} credits a redemption to the campaign named by its
 *       {@code campaignCode}.
 * </ul>
 *
 * <p>Delivery is at-least-once, so every handler is idempotent: the {@code processed_events}
 * log guards replays generally, and attribution is additionally keyed on the CRM's
 * {@code redemptionId} so a conversion can only ever be counted once.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.marketing.kafka", name = "enabled", havingValue = "true")
public class CustomerEventsListener {

    private static final String OWNER = "pos-customer";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final SuppressionReplicaRepository suppressionRepository;
    private final CampaignAttributionRepository attributionRepository;
    private final CampaignRepository campaignRepository;

    public CustomerEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            SuppressionReplicaRepository suppressionRepository,
            CampaignAttributionRepository attributionRepository,
            CampaignRepository campaignRepository) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.suppressionRepository = suppressionRepository;
        this.attributionRepository = attributionRepository;
        this.campaignRepository = campaignRepository;
    }

    @KafkaListener(
            topics = "${pos.marketing.kafka.customer-events-topic:customer.events.v1}",
            groupId = "${pos.marketing.kafka.customer-events-consumer-group:pos-marketing-customer-events}")
    @Transactional
    public void onCustomerEvent(@NonNull String message) {
        JsonNode envelope = objectMapper.readTree(message);
        String eventId = envelope.path("eventId").asString("");
        String eventType = envelope.path("eventType").asString("");
        if (eventId.isBlank() || processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate or unidentified customer event type={} id={}", eventType, eventId);
            return;
        }
        JsonNode payload = envelope.path("payload");

        switch (eventType) {
            case CustomerSuppressionChangedV1.EVENT_TYPE -> applySuppression(payload);
            case CustomerRedemptionRecordedV1.EVENT_TYPE -> applyRedemption(payload);
            default -> {
                // Other customer facts are none of this module's business; skip without recording
                // them so the log stays scoped to what was actually applied.
                return;
            }
        }

        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applySuppression(JsonNode payload) {
        String channel = payload.path("channel").asString("");
        String addressHash = payload.path("addressHash").asString("");
        if (channel.isBlank() || addressHash.isBlank()) {
            log.warn("Suppression fact missing channel or addressHash; ignoring");
            return;
        }
        String key = SuppressionReplica.keyOf(channel, addressHash);
        if (payload.path("suppressed").asBoolean(false)) {
            suppressionRepository.save(SuppressionReplica.builder()
                    .suppressionKey(key)
                    .channel(channel)
                    .addressHash(addressHash)
                    .partyId(optionalUuid(payload, "partyId"))
                    .reason(payload.path("reason").asString(null))
                    .updatedAt(Instant.now(clock))
                    .build());
        } else {
            suppressionRepository.deleteById(key);
        }
    }

    private void applyRedemption(JsonNode payload) {
        String campaignCode = payload.path("campaignCode").asString(null);
        if (campaignCode == null || campaignCode.isBlank()) {
            // Redemptions with no campaign are the baseline a campaign's lift is measured
            // against; they are published for completeness but attribute to nothing.
            return;
        }
        UUID redemptionId = optionalUuid(payload, "redemptionId");
        if (redemptionId == null) {
            log.warn("Redemption fact missing redemptionId; cannot attribute idempotently");
            return;
        }
        if (attributionRepository.existsById(redemptionId)) {
            return;
        }
        campaignRepository
                .findByCodeIgnoreCase(campaignCode)
                .ifPresentOrElse(
                        campaign -> attributionRepository.save(CampaignAttribution.builder()
                                .redemptionId(redemptionId)
                                .campaignId(campaign.getCampaignId())
                                .campaignCode(campaign.getCode())
                                .partyId(optionalUuid(payload, "customerId"))
                                .workorderId(optionalUuid(payload, "workorderId"))
                                .discountAmount(payload.path("discountAmount").decimalValue())
                                .attributedAt(Instant.now(clock))
                                .build()),
                        () -> log.info(
                                "Redemption {} references unknown campaign code {}; not attributed",
                                redemptionId,
                                campaignCode));
    }

    private static UUID optionalUuid(JsonNode payload, String field) {
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
