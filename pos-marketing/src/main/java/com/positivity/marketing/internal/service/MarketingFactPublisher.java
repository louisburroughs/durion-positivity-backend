package com.positivity.marketing.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.marketing.MarketingCampaignScheduledV1;
import com.positivity.domainevents.marketing.MarketingCampaignSendOutcomeV1;
import com.positivity.domainevents.marketing.MarketingCampaignSentV1;
import com.positivity.marketing.internal.config.OutboxEventWriter;
import com.positivity.marketing.internal.entity.Campaign;
import com.positivity.marketing.internal.entity.CampaignSend;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes marketing facts to {@code marketing.events.v1} through the transactional outbox
 * (ADR-0044 §6, Stories #1146/#1149).
 *
 * <p>When Kafka is disabled the outbox writer bean is absent and every method is a no-op, so
 * callers never need their own guard — matching the pos-customer publisher's shape.
 */
@Slf4j
@Component
public class MarketingFactPublisher {

    private static final String SOURCE = "pos-marketing";

    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final Clock clock;
    private final String eventsTopic;

    public MarketingFactPublisher(
            ObjectProvider<OutboxEventWriter> outboxEventWriter,
            Clock clock,
            @Value("${pos.marketing.kafka.events-topic:marketing.events.v1}") String eventsTopic) {
        this.outboxEventWriter = outboxEventWriter;
        this.clock = clock;
        this.eventsTopic = eventsTopic;
    }

    /** Emit {@code marketing.campaign.scheduled} for a campaign that just became schedulable. */
    public void campaignScheduled(@NonNull Campaign campaign) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        MarketingCampaignScheduledV1 payload = new MarketingCampaignScheduledV1(
                campaign.getCampaignId(),
                campaign.getCode(),
                campaign.getAudienceType().name(),
                campaign.getSegmentId(),
                campaign.getScheduledAt());
        publish(
                writer,
                MarketingCampaignScheduledV1.EVENT_TYPE,
                MarketingCampaignScheduledV1.SCHEMA_VERSION,
                campaign.getCampaignId(),
                payload);
    }

    /**
     * Emit {@code marketing.campaign.sent} for one dispatched recipient (Story #1149).
     *
     * <p>The aggregate id is the recipient party, not the campaign: pos-customer keys the
     * resulting interaction per party, and per-party ordering is what a timeline needs.
     */
    public void campaignSent(
            @NonNull CampaignSend send, @NonNull String campaignCode, @Nullable String renderedSubject) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        MarketingCampaignSentV1 payload = new MarketingCampaignSentV1(
                send.getCampaignSendId(),
                send.getCampaignId(),
                campaignCode,
                send.getRecipientPartyId(),
                send.getContactId(),
                send.getChannel().name(),
                renderedSubject);
        publish(
                writer,
                MarketingCampaignSentV1.EVENT_TYPE,
                MarketingCampaignSentV1.SCHEMA_VERSION,
                send.getRecipientPartyId(),
                payload);
    }

    /**
     * Emit {@code marketing.campaign.send.delivered/bounced/complained} for one sender outcome
     * (Story #1150). The event type mirrors the status transition just applied to the send.
     */
    public void sendOutcome(@NonNull CampaignSend send, @NonNull String campaignCode, @NonNull String eventType) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        MarketingCampaignSendOutcomeV1 payload = new MarketingCampaignSendOutcomeV1(
                send.getCampaignSendId(),
                send.getCampaignId(),
                campaignCode,
                send.getRecipientPartyId(),
                send.getChannel().name(),
                send.getStatus().name(),
                send.getFailureReason());
        publish(writer, eventType, MarketingCampaignSendOutcomeV1.SCHEMA_VERSION, send.getRecipientPartyId(), payload);
    }

    private void publish(
            @NonNull OutboxEventWriter writer,
            @NonNull String eventType,
            int schemaVersion,
            @NonNull UUID aggregateId,
            Object payload) {
        DomainEventEnvelope<Object> envelope = DomainEventEnvelope.of(
                eventType,
                schemaVersion,
                aggregateId,
                Instant.now(clock).toEpochMilli(),
                SOURCE,
                null,
                null,
                payload,
                clock);
        writer.publish(eventsTopic, envelope);
        log.debug("Queued {} for aggregate {}", eventType, aggregateId);
    }
}
