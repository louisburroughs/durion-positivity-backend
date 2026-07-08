package com.positivity.workorder.internal.config;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.workorder.internal.dto.AssignmentUpdatedEvent;
import com.positivity.workorder.service.OutboxReplayService;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka command listener for inbound workorder commands/events.
 *
 * <p>
 * Current supported command types:
 * <ul>
 * <li>{@code ASSIGNMENT_UPDATED}</li>
 * <li>{@code workorder.outbox.replay-requested} — consumer-initiated drift repair (ADR-0044 §4):
 * re-queues published outbox events created at or after {@code payload.since} for
 * re-publication; consumers dedupe by eventId so replay is idempotent.</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "workorder.kafka", name = "enabled", havingValue = "true")
public class KafkaCommandListener {

    private static final String PAYLOAD = "payload";
    private static final String COMMAND_ASSIGNMENT_UPDATED = "ASSIGNMENT_UPDATED";
    /** Canonical dotted name normalized to command-type form: WORKORDER_OUTBOX_REPLAY_REQUESTED. */
    private static final String COMMAND_OUTBOX_REPLAY_REQUESTED = "WORKORDER_OUTBOX_REPLAY_REQUESTED";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final OutboxReplayService outboxReplayService;

    @KafkaListener(
            topics = "${workorder.kafka.commands-topic:workorder.commands.v1}",
            groupId = "${workorder.kafka.consumer-group:workorder-commands}")
    public void onCommand(@NonNull String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            boolean hasCommandType = root.hasNonNull("commandType");
            String commandType = COMMAND_ASSIGNMENT_UPDATED;
            if (hasCommandType) {
                String rawCommandType = root.get("commandType").stringValue();
                if (rawCommandType != null && !rawCommandType.isBlank()) {
                    // Normalize dotted command names (workorder.outbox.replay-requested) and
                    // legacy UPPER_SNAKE names to one form.
                    commandType = rawCommandType
                            .toUpperCase(Locale.ROOT)
                            .replace('.', '_')
                            .replace('-', '_');
                }
            }

            if (COMMAND_OUTBOX_REPLAY_REQUESTED.equals(commandType)) {
                handleOutboxReplayRequested(root);
                return;
            }

            if (!COMMAND_ASSIGNMENT_UPDATED.equals(commandType)) {
                log.debug("Ignoring unsupported commandType={} message={}", commandType, message);
                return;
            }

            JsonNode payloadNode =
                    hasCommandType && root.has(PAYLOAD) && !root.get(PAYLOAD).isNull() ? root.get(PAYLOAD) : root;

            AssignmentUpdatedEvent event = objectMapper.treeToValue(payloadNode, AssignmentUpdatedEvent.class);
            if (event.getWorkorderId() == null || event.getPayload() == null) {
                log.warn("Ignoring ASSIGNMENT_UPDATED command with missing workorderId/payload: {}", message);
                return;
            }

            if (event.getEventId() == null) {
                event.setEventId(UUIDv7Generator.generate());
            }
            if (event.getTimestamp() == null) {
                event.setTimestamp(Instant.now(clock));
            }

            applicationEventPublisher.publishEvent(event);
            log.info("Published AssignmentUpdatedEvent from Kafka for workorderId={}", event.getWorkorderId());
        } catch (Exception e) {
            log.error("Failed to process Kafka command message: {}", message, e);
        }
    }

    private void handleOutboxReplayRequested(@NonNull JsonNode root) {
        JsonNode payloadNode = root.get(PAYLOAD);
        String since = payloadNode == null ? null : payloadNode.path("since").stringValue(null);
        if (since == null || since.isBlank()) {
            log.warn("Ignoring outbox replay command with missing payload.since: {}", root);
            return;
        }
        Instant sinceInstant;
        try {
            sinceInstant = Instant.parse(since);
        } catch (Exception e) {
            log.warn("Ignoring outbox replay command with malformed payload.since={}", since);
            return;
        }
        int queued = outboxReplayService.replaySince(sinceInstant);
        log.info("Outbox replay command processed since={} eventsQueued={}", sinceInstant, queued);
    }
}
