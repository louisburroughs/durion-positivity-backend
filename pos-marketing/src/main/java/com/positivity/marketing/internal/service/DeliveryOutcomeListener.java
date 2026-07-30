package com.positivity.marketing.internal.service;

import com.positivity.domainevents.marketing.MarketingCampaignSendOutcomeV1;
import com.positivity.marketing.internal.config.OutboxEventWriter;
import com.positivity.marketing.internal.entity.Campaign;
import com.positivity.marketing.internal.entity.CampaignSend;
import com.positivity.marketing.internal.entity.ProcessedEvent;
import com.positivity.marketing.internal.enums.SendStatus;
import com.positivity.marketing.internal.repository.CampaignRepository;
import com.positivity.marketing.internal.repository.CampaignSendRepository;
import com.positivity.marketing.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Consumes {@code sender.outcomes.v1} — the shared platform sender's delivery, bounce,
 * complaint, open, and click reports (Story #1150, FI-2 contract — durion#369,
 * {@code docs/PLATFORM_SENDER_CONTRACT.md}).
 *
 * <p>Each outcome correlates back to a {@code CampaignSend} row via the
 * {@code providerMessageId} returned at send time. An outcome for an id this module has never
 * seen is thrown back to the container: the send worker's transaction may simply not have
 * committed yet, so the record retries with backoff and lands on the DLQ only if it stays
 * unmatched — silently dropping it would leave the send stuck in {@code SENT} forever.
 *
 * <p>Hard bounces and complaints additionally feed the CRM suppression list (spec §4.3): a
 * {@code customer.suppression.add-requested} command goes to {@code customer.commands.v1}
 * through the outbox, in the same transaction as the status change. The raw address rides only
 * inside that command — it is never persisted here (the send table keeps a hash at most). The
 * command reuses the sender envelope's eventId, so a redelivered outcome produces the same
 * command, and pos-customer's idempotent add makes the replay harmless.
 *
 * <p>Opens and clicks are best-effort engagement signals: first occurrence stamps
 * {@code openedAt}/{@code clickedAt}, later ones are ignored, and a sender that never reports
 * them (SMS, privacy-proxied email) costs nothing — the funnel just shows the columns as
 * untracked. Delivery is at-least-once, so everything here is idempotent via
 * {@code processed_events}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.marketing.kafka", name = "enabled", havingValue = "true")
public class DeliveryOutcomeListener {

    private static final String OWNER = "platform-sender";

    static final String OUTCOME_DELIVERED = "sender.message.delivered";
    static final String OUTCOME_BOUNCED = "sender.message.bounced";
    static final String OUTCOME_COMPLAINED = "sender.message.complained";
    static final String OUTCOME_OPENED = "sender.message.opened";
    static final String OUTCOME_CLICKED = "sender.message.clicked";

    private static final String SUPPRESSION_COMMAND_TYPE = "customer.suppression.add-requested";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final CampaignSendRepository sendRepository;
    private final CampaignRepository campaignRepository;
    private final MarketingFactPublisher factPublisher;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final String customerCommandsTopic;

    public DeliveryOutcomeListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            CampaignSendRepository sendRepository,
            CampaignRepository campaignRepository,
            MarketingFactPublisher factPublisher,
            ObjectProvider<OutboxEventWriter> outboxEventWriter,
            @Value("${pos.marketing.kafka.customer-commands-topic:customer.commands.v1}")
                    String customerCommandsTopic) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.sendRepository = sendRepository;
        this.campaignRepository = campaignRepository;
        this.factPublisher = factPublisher;
        this.outboxEventWriter = outboxEventWriter;
        this.customerCommandsTopic = customerCommandsTopic;
    }

    @KafkaListener(
            topics = "${pos.marketing.kafka.sender-outcomes-topic:sender.outcomes.v1}",
            groupId = "${pos.marketing.kafka.sender-outcomes-consumer-group:pos-marketing-sender-outcomes}")
    @Transactional
    public void onSenderOutcome(@NonNull String message) {
        JsonNode envelope = objectMapper.readTree(message);
        String eventId = envelope.path("eventId").asString("");
        String eventType = envelope.path("eventType").asString("");
        if (eventId.isBlank() || processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate or unidentified sender outcome type={} id={}", eventType, eventId);
            return;
        }
        boolean relevant =
                switch (eventType) {
                    case OUTCOME_DELIVERED, OUTCOME_BOUNCED, OUTCOME_COMPLAINED, OUTCOME_OPENED, OUTCOME_CLICKED ->
                        true;
                    default -> false;
                };
        if (!relevant) {
            // The sender may add outcome kinds this module doesn't consume; skip without
            // recording them so the processed log stays scoped to what was actually applied.
            return;
        }

        JsonNode payload = envelope.path("payload");
        String providerMessageId = payload.path("providerMessageId").asString("");
        if (providerMessageId.isBlank()) {
            log.warn("Ignoring sender outcome {} without providerMessageId: {}", eventType, eventId);
            return;
        }
        CampaignSend send = sendRepository
                .findByProviderMessageId(providerMessageId)
                // Retryable on purpose: the outcome may have raced the send worker's commit.
                .orElseThrow(() -> new IllegalStateException("No campaign send for providerMessageId="
                        + providerMessageId + " (outcome " + eventType + ")"));

        switch (eventType) {
            case OUTCOME_DELIVERED -> applyDelivered(send, payload);
            case OUTCOME_BOUNCED -> applyRejection(send, payload, SendStatus.BOUNCED, eventId);
            case OUTCOME_COMPLAINED -> applyRejection(send, payload, SendStatus.COMPLAINED, eventId);
            case OUTCOME_OPENED -> applyEngagement(send, payload, true);
            case OUTCOME_CLICKED -> applyEngagement(send, payload, false);
            default -> throw new IllegalStateException("Unhandled outcome type " + eventType);
        }

        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyDelivered(CampaignSend send, JsonNode payload) {
        // A bounce or complaint outranks delivery; out-of-order or duplicate delivery
        // reports must not resurrect the send.
        if (send.getStatus() != SendStatus.SENT) {
            log.debug("Ignoring delivery report for send {} in status {}", send.getCampaignSendId(), send.getStatus());
            return;
        }
        send.setStatus(SendStatus.DELIVERED);
        send.setDeliveredAt(occurredAt(payload));
        touch(send);
        sendRepository.save(send);
        factPublisher.sendOutcome(send, campaignCode(send), MarketingCampaignSendOutcomeV1.EVENT_TYPE_DELIVERED);
    }

    /** Bounce and complaint share a shape: terminal status, optional reason, suppression feed. */
    private void applyRejection(CampaignSend send, JsonNode payload, SendStatus newStatus, String eventId) {
        // A complaint can follow a delivery (the customer read it, then hit "spam"); anything
        // already bounced/complained/failed stays as-is.
        if (send.getStatus() != SendStatus.SENT && send.getStatus() != SendStatus.DELIVERED) {
            log.debug(
                    "Ignoring {} report for send {} in status {}",
                    newStatus,
                    send.getCampaignSendId(),
                    send.getStatus());
            return;
        }
        send.setStatus(newStatus);
        send.setFailureReason(payload.path("reason").asString(null));
        touch(send);
        sendRepository.save(send);

        String eventType = newStatus == SendStatus.BOUNCED
                ? MarketingCampaignSendOutcomeV1.EVENT_TYPE_BOUNCED
                : MarketingCampaignSendOutcomeV1.EVENT_TYPE_COMPLAINED;
        factPublisher.sendOutcome(send, campaignCode(send), eventType);

        // Only a hard bounce protects deliverability by suppressing the address; a soft
        // bounce (full mailbox, greylisting) must not hard-block someone forever. The
        // sender says which it was; absence of the flag is treated as hard — failing
        // toward suppression is the safe direction for reputation and compliance.
        boolean permanent = payload.path("permanent").asBoolean(true);
        if (newStatus == SendStatus.COMPLAINED || permanent) {
            requestSuppression(send, payload, newStatus, eventId);
        }
    }

    private void applyEngagement(CampaignSend send, JsonNode payload, boolean opened) {
        Instant at = occurredAt(payload);
        boolean changed = false;
        if (opened && send.getOpenedAt() == null) {
            send.setOpenedAt(at);
            changed = true;
        }
        // A click implies an open even when the open pixel was blocked.
        if (!opened && send.getClickedAt() == null) {
            send.setClickedAt(at);
            if (send.getOpenedAt() == null) {
                send.setOpenedAt(at);
            }
            changed = true;
        }
        if (changed) {
            touch(send);
            sendRepository.save(send);
        }
    }

    /**
     * Queue {@code customer.suppression.add-requested} on {@code customer.commands.v1}. The raw
     * address arrives in the outcome payload solely for this hand-off; when the sender omitted
     * it there is nothing to suppress by and the outcome is only recorded locally.
     */
    private void requestSuppression(CampaignSend send, JsonNode payload, SendStatus newStatus, String eventId) {
        String address = payload.path("address").asString("");
        if (address.isBlank()) {
            log.warn(
                    "Sender {} outcome for send {} carried no address; CRM suppression skipped",
                    newStatus,
                    send.getCampaignSendId());
            return;
        }
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        ObjectNode command = objectMapper.createObjectNode();
        command.put("commandType", SUPPRESSION_COMMAND_TYPE);
        command.put("eventId", eventId);
        ObjectNode commandPayload = command.putObject("payload");
        commandPayload.put("channel", send.getChannel().name());
        commandPayload.put("address", address);
        commandPayload.put("partyId", send.getRecipientPartyId().toString());
        commandPayload.put("reason", newStatus == SendStatus.COMPLAINED ? "SPAM_COMPLAINT" : "HARD_BOUNCE");
        writer.publishRaw(
                customerCommandsTopic, send.getRecipientPartyId().toString(), objectMapper.writeValueAsString(command));
    }

    private @NonNull String campaignCode(CampaignSend send) {
        return campaignRepository
                .findById(send.getCampaignId())
                .map(Campaign::getCode)
                .orElse("UNKNOWN");
    }

    private @NonNull Instant occurredAt(@Nullable JsonNode payload) {
        String value = payload == null ? null : payload.path("occurredAt").asString(null);
        if (value != null && !value.isBlank()) {
            try {
                return Instant.parse(value);
            } catch (Exception _) {
                log.debug("Malformed occurredAt '{}' on sender outcome; using now", value);
            }
        }
        return Instant.now(clock);
    }

    private void touch(CampaignSend send) {
        send.setUpdatedAt(Instant.now(clock));
    }
}
