package com.positivity.tax.internal.config;

import com.positivity.kafka.common.KafkaRails;
import com.positivity.tenancy.kafka.RetryableConsumerFailures;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka command listener for {@code tax.commands.v1} (ADR-0044 §4; CAP:550 S32c): {@code
 * tax.outbox.replay-requested}, a consumer's drift repair after its {@code ext_tax_registration} copy disagreed
 * with a {@code tax.manifest.v1} window ({@link ManifestPublisher}). The window's facts of the record's tenant are
 * re-queued on the outbox; consumers dedupe by eventId and apply by version, so a replay is idempotent.
 *
 * <p>A command is never logged whole: it carries only the window, but the listener logs what it read field by field
 * so a malformed message cannot put anything else into the log.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@KafkaRails
public class TaxCommandListener {

    /** Wire name {@code tax.outbox.replay-requested}, in normalized command-type form. */
    static final String COMMAND_OUTBOX_REPLAY_REQUESTED = "TAX_OUTBOX_REPLAY_REQUESTED";

    /** Covers the sub-millisecond skew between outbox createdAt and the eventId timestamp. */
    private static final Duration REPLAY_WINDOW_SLACK = Duration.ofSeconds(1);

    /** Replay commands older than this are rejected — bounds repair cost. */
    @Value("${pos.tax.outbox.replay.max-lookback:P30D}")
    private Duration replayMaxLookback = Duration.ofDays(30);

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final OutboxReplayService outboxReplayService;

    @KafkaListener(
            topics = "${pos.tax.kafka.commands-topic:tax.commands.v1}",
            groupId = "${pos.tax.kafka.commands-consumer-group:pos-tax-commands}")
    public void onCommand(@NonNull String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String rawCommandType = root.path("commandType").stringValue(null);
            if (rawCommandType == null || rawCommandType.isBlank()) {
                log.debug("Ignoring command without commandType");
                return;
            }
            String commandType =
                    rawCommandType.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
            if (COMMAND_OUTBOX_REPLAY_REQUESTED.equals(commandType)) {
                handleOutboxReplayRequested(root);
                return;
            }
            log.debug("Ignoring unsupported commandType={}", commandType);
        } catch (Exception e) {
            if (RetryableConsumerFailures.isRetryable(e)) {
                // The container retries with backoff, then dead-letters to {topic}.dlq (ADR-0044 §4);
                // a replay is idempotent, so redelivery is harmless.
                throw e;
            }
            log.error("Failed to process tax command message", e);
        }
    }

    private void handleOutboxReplayRequested(@NonNull JsonNode root) {
        JsonNode payloadNode = root.get("payload");
        Instant since = parseInstant(payloadNode, "since");
        if (since == null) {
            log.warn("Ignoring outbox replay command with missing/malformed payload.since");
            return;
        }
        Instant lookbackLimit = Instant.now(clock).minus(replayMaxLookback);
        if (since.isBefore(lookbackLimit)) {
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
            queued = outboxReplayService.replayBetween(
                    since.minus(REPLAY_WINDOW_SLACK), until.plus(REPLAY_WINDOW_SLACK));
        } else {
            queued = outboxReplayService.replaySince(since.minus(REPLAY_WINDOW_SLACK));
        }
        log.info("Tax outbox replay command processed since={} until={} eventsQueued={}", since, until, queued);
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
