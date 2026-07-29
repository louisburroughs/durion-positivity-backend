package com.positivity.customer.internal.config;

import com.positivity.customer.service.OutboxReplayService;
import com.positivity.customer.service.SegmentService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka command listener for {@code customer.commands.v1} (ADR-0044 §4, issue #889).
 *
 * <p>Supported command types:
 * <ul>
 * <li>{@code customer.outbox.replay-requested} — consumer-initiated drift repair and replica
 * bootstrap: re-queues published outbox events created in the requested window for
 * re-publication; consumers dedupe by eventId so replay is idempotent.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.customer.kafka", name = "enabled", havingValue = "true")
public class CustomerCommandListener {

    /** Canonical dotted name normalized to command-type form: CUSTOMER_OUTBOX_REPLAY_REQUESTED. */
    private static final String COMMAND_OUTBOX_REPLAY_REQUESTED = "CUSTOMER_OUTBOX_REPLAY_REQUESTED";

    /**
     * Asynchronous membership request (Story #1137). Dynamic segment membership is derived
     * from party data and has no event boundary, so it cannot be replicated continuously —
     * a requester asks and this module answers with {@code customer.segment.resolved}.
     */
    private static final String COMMAND_SEGMENT_RESOLVE_REQUESTED = "CUSTOMER_SEGMENT_RESOLVE_REQUESTED";

    /** Covers the sub-millisecond skew between outbox createdAt and the eventId timestamp. */
    private static final Duration REPLAY_WINDOW_SLACK = Duration.ofSeconds(1);

    /** Replay commands older than this are rejected — bounds repair cost. */
    @Value("${pos.customer.outbox.replay.max-lookback:P30D}")
    private Duration replayMaxLookback;

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final OutboxReplayService outboxReplayService;
    private final SegmentService segmentService;

    @KafkaListener(
            topics = "${pos.customer.kafka.commands-topic:customer.commands.v1}",
            groupId = "${pos.customer.kafka.commands-consumer-group:pos-customer-commands}")
    public void onCommand(@NonNull String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String rawCommandType = root.path("commandType").stringValue(null);
            if (rawCommandType == null || rawCommandType.isBlank()) {
                log.debug("Ignoring command without commandType: {}", message);
                return;
            }
            // Normalize dotted command names (customer.outbox.replay-requested) to one form.
            String commandType =
                    rawCommandType.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');

            if (COMMAND_OUTBOX_REPLAY_REQUESTED.equals(commandType)) {
                handleOutboxReplayRequested(root);
                return;
            }
            if (COMMAND_SEGMENT_RESOLVE_REQUESTED.equals(commandType)) {
                handleSegmentResolveRequested(root);
                return;
            }
            log.debug("Ignoring unsupported commandType={} message={}", commandType, message);
        } catch (TransientDataAccessException e) {
            // Let the container error handler retry with backoff and route to {topic}.dlq
            // (ADR-0044 §4) — replay is idempotent, so redelivery is harmless.
            throw e;
        } catch (Exception e) {
            // Malformed/unsupported commands are permanent failures: retrying cannot fix them,
            // so log and drop instead of poisoning the partition.
            log.error("Failed to process Kafka command message: {}", message, e);
        }
    }

    private void handleOutboxReplayRequested(@NonNull JsonNode root) {
        JsonNode payloadNode = root.get("payload");
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
        } catch (Exception _) {
            log.warn("Malformed payload.{}={} on outbox replay command", field, value);
            return null;
        }
    }

    /**
     * Resolve a segment and reply with {@code customer.segment.resolved}.
     *
     * <p>Runs in its own transaction so the reply fact is written to the outbox atomically with
     * the read that produced it. A malformed or unknown request is dropped rather than retried:
     * neither can be fixed by redelivery, and a requester waiting on a reply will time out and
     * ask again.
     */
    @Transactional
    void handleSegmentResolveRequested(JsonNode root) {
        JsonNode payload = root.path("payload");
        UUID requestId = uuidOrNull(payload, "requestId");
        UUID segmentId = uuidOrNull(payload, "segmentId");
        if (requestId == null || segmentId == null) {
            log.warn("Ignoring segment-resolve command missing requestId or segmentId");
            return;
        }
        try {
            segmentService
                    .resolveAndPublish(requestId, segmentId)
                    .ifPresent(count ->
                            log.info("Resolved segment {} for request {}: {} party(ies)", segmentId, requestId, count));
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Segment {} could not be resolved for request {}: {}", segmentId, requestId, e.getMessage());
        }
    }

    private static UUID uuidOrNull(JsonNode node, String field) {
        String value = node.path(field).stringValue(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
