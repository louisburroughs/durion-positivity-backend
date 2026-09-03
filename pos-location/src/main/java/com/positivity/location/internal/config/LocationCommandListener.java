package com.positivity.location.internal.config;

import com.positivity.location.internal.config.FactBackfillService.BackfillResult;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka command listener for {@code location.commands.v1} (ADR-0044 §4, issue #890).
 *
 * <p>Supported command types:
 * <ul>
 * <li>{@code location.outbox.replay-requested} — consumer-initiated drift repair and replica
 * bootstrap: re-queues published outbox events created in the requested window for
 * re-publication; consumers dedupe by eventId so replay is idempotent.</li>
 * <li>{@code location.fact-backfill.requested} — regenerate-from-state seeding for bay and
 * mobile-unit replicas (issue #1668). Distinct from outbox replay, which can only re-send facts
 * that were published at least once: bays and mobile units that existed before #1668 have no
 * outbox history, so replay cannot reach them. {@code payload.aggregate} selects
 * {@code bay}, {@code mobile-unit}, or {@code all} (the default); optional
 * {@code payload.afterId} resumes a bounded run from the cursor the previous run logged.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.location.kafka", name = "enabled", havingValue = "true")
public class LocationCommandListener {

    /** Wire name {@code location.outbox.replay-requested}, in normalized command-type form. */
    private static final String COMMAND_OUTBOX_REPLAY_REQUESTED = "LOCATION_OUTBOX_REPLAY_REQUESTED";

    /** Wire name {@code location.fact-backfill.requested}, in normalized command-type form. */
    private static final String COMMAND_FACT_BACKFILL_REQUESTED = "LOCATION_FACT_BACKFILL_REQUESTED";

    private static final String AGGREGATE_BAY = "bay";
    private static final String AGGREGATE_MOBILE_UNIT = "mobile-unit";
    private static final String AGGREGATE_ALL = "all";

    /** Covers the sub-millisecond skew between outbox createdAt and the eventId timestamp. */
    private static final Duration REPLAY_WINDOW_SLACK = Duration.ofSeconds(1);

    /** Replay commands older than this are rejected — bounds repair cost. */
    @Value("${pos.location.outbox.replay.max-lookback:P30D}")
    private Duration replayMaxLookback;

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final OutboxReplayService outboxReplayService;
    private final FactBackfillService factBackfillService;

    @KafkaListener(
            topics = "${pos.location.kafka.commands-topic:location.commands.v1}",
            groupId = "${pos.location.kafka.commands-consumer-group:pos-location-commands}")
    public void onCommand(@NonNull String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String rawCommandType = root.path("commandType").stringValue(null);
            if (rawCommandType == null || rawCommandType.isBlank()) {
                log.debug("Ignoring command without commandType: {}", message);
                return;
            }
            // Normalize dotted command names (location.outbox.replay-requested) to one form.
            String commandType =
                    rawCommandType.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');

            if (COMMAND_OUTBOX_REPLAY_REQUESTED.equals(commandType)) {
                handleOutboxReplayRequested(root);
                return;
            }
            if (COMMAND_FACT_BACKFILL_REQUESTED.equals(commandType)) {
                handleFactBackfillRequested(root);
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

    /**
     * Re-emit current-state facts so a consumer starting from an empty replica converges.
     *
     * <p>Unbounded by design, unlike outbox replay's {@code max-lookback} guard: the whole point is
     * to reach rows that predate the producer, so there is no time window that could contain them.
     * The cost is bounded by the number of bays and mobile units the module owns, which is
     * operationally small, and the operation is idempotent — a replica applies an equal version and
     * skips a strictly-greater one, so re-running repairs without duplicating.
     */
    private void handleFactBackfillRequested(@NonNull JsonNode root) {
        JsonNode payloadNode = root.get("payload");
        String rawAggregate =
                payloadNode == null ? null : payloadNode.path("aggregate").stringValue(null);
        String aggregate = rawAggregate == null || rawAggregate.isBlank()
                ? AGGREGATE_ALL
                : rawAggregate.trim().toLowerCase(Locale.ROOT);

        if (!AGGREGATE_ALL.equals(aggregate)
                && !AGGREGATE_BAY.equals(aggregate)
                && !AGGREGATE_MOBILE_UNIT.equals(aggregate)) {
            // Unknown selector: a typo must not silently backfill everything.
            log.warn("Ignoring fact backfill command with unsupported payload.aggregate={}", rawAggregate);
            return;
        }

        UUID afterId = parseUuid(payloadNode);
        BackfillResult bays = null;
        BackfillResult mobileUnits = null;
        if (AGGREGATE_ALL.equals(aggregate) || AGGREGATE_BAY.equals(aggregate)) {
            bays = factBackfillService.backfillBays(afterId);
        }
        if (AGGREGATE_ALL.equals(aggregate) || AGGREGATE_MOBILE_UNIT.equals(aggregate)) {
            mobileUnits = factBackfillService.backfillMobileUnits(afterId);
        }
        // A run stops at the configured bound so it cannot outlive max.poll.interval.ms and get the
        // consumer evicted. When rows remain, the operator re-sends the command with afterId set to
        // the cursor logged here; the run is idempotent, so an overlapping resume is harmless.
        log.info(
                "Fact backfill command processed aggregate={} afterId={} bays={} mobileUnits={}",
                aggregate,
                afterId,
                describe(bays),
                describe(mobileUnits));
        if (hasMore(bays) || hasMore(mobileUnits)) {
            log.warn(
                    "Fact backfill hit its per-run bound; re-send location.fact-backfill.requested "
                            + "with payload.afterId to continue (bays={}, mobileUnits={})",
                    describe(bays),
                    describe(mobileUnits));
        }
    }

    private static boolean hasMore(@Nullable BackfillResult result) {
        return result != null && result.more();
    }

    private static String describe(@Nullable BackfillResult result) {
        if (result == null) {
            return "skipped";
        }
        return result.published() + " queued, lastId=" + result.lastId() + ", more=" + result.more();
    }

    private @Nullable UUID parseUuid(@Nullable JsonNode payloadNode) {
        String value = payloadNode == null ? null : payloadNode.path("afterId").stringValue(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException _) {
            // Resuming from the beginning is safe -- the run is idempotent -- but say so, because
            // silently restarting a large walk is not what the operator asked for.
            log.warn("Malformed payload.afterId={} on fact backfill command; starting from the beginning", value);
            return null;
        }
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
}
