package com.positivity.workorder.internal.config;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.positivity.workorder.internal.dto.AssignmentUpdatedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka command listener for inbound workorder commands/events.
 *
 * <p>
 * Current supported command type:
 * <ul>
 * <li>{@code ASSIGNMENT_UPDATED}</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "workorder.kafka", name = "enabled", havingValue = "true")
public class WorkorderKafkaCommandListener {

    private static final String PAYLOAD = "payload";
    private static final String COMMAND_ASSIGNMENT_UPDATED = "ASSIGNMENT_UPDATED";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;

    @KafkaListener(topics = "${workorder.kafka.commands-topic:workorder.commands.v1}", groupId = "${workorder.kafka.consumer-group:workorder-commands}")
    public void onCommand(@NonNull String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            boolean hasCommandType = root.hasNonNull("commandType");
            String commandType = COMMAND_ASSIGNMENT_UPDATED;
            if (hasCommandType) {
                String rawCommandType = root.get("commandType").stringValue();
                if (rawCommandType != null && !rawCommandType.isBlank()) {
                    commandType = rawCommandType.toUpperCase(Locale.ROOT);
                }
            }

            if (!COMMAND_ASSIGNMENT_UPDATED.equals(commandType)) {
                log.debug("Ignoring unsupported commandType={} message={}", commandType, message);
                return;
            }

            JsonNode payloadNode = hasCommandType
                    && root.has(PAYLOAD)
                    && !root.get(PAYLOAD).isNull()
                            ? root.get(PAYLOAD)
                            : root;

            AssignmentUpdatedEvent event = objectMapper.treeToValue(payloadNode, AssignmentUpdatedEvent.class);
            if (event.getWorkorderId() == null || event.getPayload() == null) {
                log.warn("Ignoring ASSIGNMENT_UPDATED command with missing workorderId/payload: {}", message);
                return;
            }

            if (event.getEventId() == null) {
                event.setEventId(UUID.randomUUID());
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
}
