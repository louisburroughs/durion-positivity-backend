package com.positivity.marketing.internal.service;

import com.positivity.domainevents.customer.CustomerConsentDecisionChangedV1;
import com.positivity.domainevents.customer.CustomerRedemptionRecordedV1;
import com.positivity.domainevents.customer.CustomerSegmentChangedV1;
import com.positivity.domainevents.customer.CustomerSegmentResolvedV1;
import com.positivity.domainevents.customer.CustomerSuppressionChangedV1;
import com.positivity.marketing.internal.entity.Campaign;
import com.positivity.marketing.internal.entity.CampaignAttribution;
import com.positivity.marketing.internal.entity.CampaignAudienceMember;
import com.positivity.marketing.internal.entity.PartyConsentReplica;
import com.positivity.marketing.internal.entity.ProcessedEvent;
import com.positivity.marketing.internal.entity.SegmentReplica;
import com.positivity.marketing.internal.entity.SuppressionReplica;
import com.positivity.marketing.internal.repository.CampaignAttributionRepository;
import com.positivity.marketing.internal.repository.CampaignAudienceMemberRepository;
import com.positivity.marketing.internal.repository.CampaignRepository;
import com.positivity.marketing.internal.repository.PartyConsentReplicaRepository;
import com.positivity.marketing.internal.repository.ProcessedEventRepository;
import com.positivity.marketing.internal.repository.SegmentReplicaRepository;
import com.positivity.marketing.internal.repository.SuppressionReplicaRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * Consumes {@code customer.events.v1} — the whole of this module's CRM integration
 * (ADR-0044 R3).
 *
 * <p>Four facts matter:
 *
 * <ul>
 *   <li>{@code customer.suppression.changed} maintains {@code ext_suppression}.
 *   <li>{@code customer.consent.decision-changed} maintains {@code ext_party_consent}, holding
 *       the CRM's resolved decision rather than its inputs.
 *   <li>{@code customer.segment.changed} maintains {@code ext_segment} — metadata only.
 *   <li>{@code customer.segment.resolved} materializes a campaign audience snapshot in reply to
 *       a resolve request.
 * </ul>
 *
 * <p>Delivery is at-least-once, so every handler is idempotent: the {@code processed_events}
 * log guards replays, and attribution is additionally keyed on the CRM's {@code redemptionId}
 * so a conversion can only ever be counted once.
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
    private final PartyConsentReplicaRepository consentRepository;
    private final SegmentReplicaRepository segmentRepository;
    private final CampaignAudienceMemberRepository audienceRepository;
    private final CampaignAttributionRepository attributionRepository;
    private final CampaignRepository campaignRepository;

    public CustomerEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            SuppressionReplicaRepository suppressionRepository,
            PartyConsentReplicaRepository consentRepository,
            SegmentReplicaRepository segmentRepository,
            CampaignAudienceMemberRepository audienceRepository,
            CampaignAttributionRepository attributionRepository,
            CampaignRepository campaignRepository) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.suppressionRepository = suppressionRepository;
        this.consentRepository = consentRepository;
        this.segmentRepository = segmentRepository;
        this.audienceRepository = audienceRepository;
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
            case CustomerConsentDecisionChangedV1.EVENT_TYPE -> applyConsentDecision(payload, envelope);
            case CustomerSegmentChangedV1.EVENT_TYPE -> applySegment(payload);
            case CustomerSegmentResolvedV1.EVENT_TYPE -> applySegmentResolved(payload);
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

    /**
     * Replicate the CRM's resolved eligibility.
     *
     * <p>{@code decidedAt} is taken from the envelope's {@code occurredAtUtc} rather than local
     * time: the send worker's staleness check needs to know when the <em>owner</em> decided, and
     * stamping arrival time would make a backlogged consumer look perfectly fresh.
     */
    private void applyConsentDecision(JsonNode payload, JsonNode envelope) {
        UUID partyId = optionalUuid(payload, "partyId");
        String channel = payload.path("channel").asString("");
        if (partyId == null || channel.isBlank()) {
            log.warn("Consent-decision fact missing partyId or channel; ignoring");
            return;
        }
        consentRepository.save(PartyConsentReplica.builder()
                .consentKey(PartyConsentReplica.keyOf(partyId, channel))
                .partyId(partyId)
                .channel(channel)
                .allowed(payload.path("allowed").asBoolean(false))
                .reason(payload.path("reason").asString("UNKNOWN"))
                .governingPartyId(optionalUuid(payload, "governingPartyId"))
                .decidedAt(occurredAt(envelope))
                .build());
    }

    private void applySegment(JsonNode payload) {
        UUID segmentId = optionalUuid(payload, "segmentId");
        if (segmentId == null) {
            log.warn("Segment fact missing segmentId; ignoring");
            return;
        }
        if (payload.path("deleted").asBoolean(false)) {
            segmentRepository.deleteById(segmentId);
            return;
        }
        segmentRepository.save(SegmentReplica.builder()
                .segmentId(segmentId)
                .name(payload.path("name").asString(null))
                .audienceType(payload.path("audienceType").asString(null))
                .type(payload.path("type").asString(null))
                .active(payload.path("active").asBoolean(false))
                .updatedAt(Instant.now(clock))
                .build());
    }

    /**
     * Materialize an audience snapshot for every campaign bound to the resolved segment.
     *
     * <p>Matched by segment rather than by the request's campaign id, because two campaigns can
     * share a segment and both deserve the fresher data. Campaigns already dispatching or
     * finished are skipped: replacing an audience mid-send would change who is contacted
     * partway through, which nobody asked for.
     */
    private void applySegmentResolved(JsonNode payload) {
        UUID segmentId = optionalUuid(payload, "segmentId");
        if (segmentId == null) {
            log.warn("Segment-resolved fact missing segmentId; ignoring");
            return;
        }
        List<UUID> partyIds = new ArrayList<>();
        for (JsonNode node : payload.path("partyIds")) {
            String value = node.asString(null);
            if (value != null && !value.isBlank()) {
                try {
                    partyIds.add(UUID.fromString(value));
                } catch (IllegalArgumentException ignored) {
                    log.warn("Segment-resolved fact carried a malformed party id; skipping it");
                }
            }
        }
        Instant resolvedAt = Instant.now(clock);
        for (Campaign campaign : campaignRepository.findAll()) {
            if (!segmentId.equals(campaign.getSegmentId())
                    || !campaign.getStatus().audienceIsMutable()) {
                continue;
            }
            UUID campaignId = campaign.getCampaignId();
            audienceRepository.deleteByCampaignId(campaignId);
            audienceRepository.saveAll(partyIds.stream()
                    .map(partyId -> CampaignAudienceMember.builder()
                            .campaignId(campaignId)
                            .partyId(partyId)
                            .resolvedAt(resolvedAt)
                            .build())
                    .toList());
            log.info("Campaign {} audience snapshot refreshed: {} member(s)", campaignId, partyIds.size());
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

    private Instant occurredAt(JsonNode envelope) {
        String value = envelope.path("occurredAtUtc").asString(null);
        if (value == null || value.isBlank()) {
            return Instant.now(clock);
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return Instant.now(clock);
        }
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
