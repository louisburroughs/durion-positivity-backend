package com.positivity.workorder.internal.config;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.workorder.internal.dto.AssignmentUpdatedEvent;
import com.positivity.workorder.service.OutboxReplayService;
import com.positivity.workorder.service.WorkorderInvoiceService;
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
 * <li>{@code workorder.invoice.regenerate-requested} — async invoice regeneration (ADR-0044 R4,
 * #842): generates the invoice draft for {@code payload.workorderId}; idempotent per workorder,
 * and the result flows back to callers via {@code invoice.events.v1}.</li>
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

    /** Canonical dotted name normalized: workorder.invoice.regenerate-requested (#842). */
    private static final String COMMAND_INVOICE_REGENERATE_REQUESTED = "WORKORDER_INVOICE_REGENERATE_REQUESTED";

    /** Covers the sub-millisecond skew between outbox createdAt and the eventId timestamp. */
    private static final Duration REPLAY_WINDOW_SLACK = Duration.ofSeconds(1);

    /** Replay commands older than this are rejected (PR #849 review — bound repair cost). */
    @Value("${workorder.outbox.replay.max-lookback:P30D}")
    private Duration replayMaxLookback;

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final OutboxReplayService outboxReplayService;
    private final WorkorderInvoiceService workorderInvoiceService;

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

            if (COMMAND_INVOICE_REGENERATE_REQUESTED.equals(commandType)) {
                handleInvoiceRegenerateRequested(root);
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

    private void handleInvoiceRegenerateRequested(@NonNull JsonNode root) {
        JsonNode payloadNode = root.get(PAYLOAD);
        String rawWorkorderId =
                payloadNode == null ? null : payloadNode.path("workorderId").stringValue(null);
        UUID workorderId;
        try {
            workorderId = UUID.fromString(rawWorkorderId);
        } catch (Exception e) {
            log.warn("Ignoring invoice regeneration command with missing/malformed workorderId: {}", root);
            return;
        }
        String idempotencyKey = payloadNode.path("idempotencyKey").stringValue(null);
        try {
            // Idempotent per workorder: generateInvoice returns the existing invoice on replay,
            // so command redelivery is harmless. Business failures (workorder missing or not
            // COMPLETED) are permanent — log and drop rather than poison the partition.
            var response = workorderInvoiceService.generateInvoice(workorderId, idempotencyKey);
            log.info(
                    "Invoice regeneration command processed workorderId={} invoiceId={} status={}",
                    workorderId,
                    response.getInvoiceId(),
                    response.getStatus());
        } catch (Exception e) {
            log.error("Invoice regeneration command failed for workorderId={}", workorderId, e);
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
            // PR #849 review: a malformed or ancient `since` must not trigger a huge re-emit.
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
