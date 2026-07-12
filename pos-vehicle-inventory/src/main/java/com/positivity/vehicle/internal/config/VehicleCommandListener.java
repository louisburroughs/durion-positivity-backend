package com.positivity.vehicle.internal.config;

import com.positivity.vehicle.service.OutboxReplayService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka command listener for {@code vehicle.commands.v1} (ADR-0044 §4, issue #843).
 *
 * <p>Supported command types:
 * <ul>
 * <li>{@code vehicle.outbox.replay-requested} — consumer-initiated drift repair and replica
 * bootstrap: re-queues published outbox events created in the requested window for
 * re-publication; consumers dedupe by eventId so replay is idempotent.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.vehicle-inventory.kafka", name = "enabled", havingValue = "true")
public class VehicleCommandListener {

    private static final String PAYLOAD = "payload";

    /** Canonical dotted name normalized to command-type form: VEHICLE_OUTBOX_REPLAY_REQUESTED. */
    private static final String COMMAND_OUTBOX_REPLAY_REQUESTED = "VEHICLE_OUTBOX_REPLAY_REQUESTED";

    /** Covers the sub-millisecond skew between outbox createdAt and the eventId timestamp. */
    private static final Duration REPLAY_WINDOW_SLACK = Duration.ofSeconds(1);

    /** Replay commands older than this are rejected — bounds repair cost. */
    @Value("${pos.vehicle-inventory.outbox.replay.max-lookback:P30D}")
    private Duration replayMaxLookback;

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final OutboxReplayService outboxReplayService;

    @KafkaListener(
            topics = "${pos.vehicle-inventory.kafka.commands-topic:vehicle.commands.v1}",
            groupId = "${pos.vehicle-inventory.kafka.commands-consumer-group:pos-vehicle-inventory-commands}")
    public void onCommand(@NonNull String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String rawCommandType = root.path("commandType").stringValue(null);
            if (rawCommandType == null || rawCommandType.isBlank()) {
                log.debug("Ignoring command without commandType: {}", message);
                return;
            }
            // Normalize dotted command names (vehicle.outbox.replay-requested) to one form.
            String commandType =
                    rawCommandType.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');

            if (COMMAND_OUTBOX_REPLAY_REQUESTED.equals(commandType)) {
                handleOutboxReplayRequested(root);
                return;
            }
            log.debug("Ignoring unsupported commandType={} message={}", commandType, message);
        } catch (Exception e) {
            log.error("Failed to process Kafka command message: {}", message, e);
        }
    }

    private void handleOutboxReplayRequested(@NonNull JsonNode root) {
        JsonNode payloadNode = root.get(PAYLOAD);
        Instant since = parseInstant(payloadNode, "since");
        if (since == null) {
            log.warn("Ignoring outbox replay command with missing/malformed payload.since: {}", root);
            return;
        }
        Instant lookbackLimit = Instant.now(clock).minus(replayMaxLookback);
        if (since.isBefore(lookbackLimit)) {
            // A malformed or ancient `since` must not trigger a huge re-emit.
            log.warn(
                    "Ignoring outbox replay command: since={} exceeds max lookback {} (limit {})",
                    since,
                    replayMaxLookback,
                    lookbackLimit);
            return;
        }
        Instant until = parseInstant(payloadNode, "until");
        int queued;
        if (until != null && until.isAfter(since)) {
            // Bounded window repair; +/- slack covers createdAt vs eventId-timestamp skew.
            queued = outboxReplayService.replayBetween(
                    since.minus(REPLAY_WINDOW_SLACK), until.plus(REPLAY_WINDOW_SLACK));
        } else {
            queued = outboxReplayService.replaySince(since.minus(REPLAY_WINDOW_SLACK));
        }
        log.info("Outbox replay command processed since={} until={} eventsQueued={}", since, until, queued);
    }

    private @Nullable Instant parseInstant(@Nullable JsonNode payloadNode, @NonNull String field) {
        String value = payloadNode == null ? null : payloadNode.path(field).stringValue(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            log.warn("Malformed payload.{}={} on outbox replay command", field, value);
            return null;
        }
    }
}
