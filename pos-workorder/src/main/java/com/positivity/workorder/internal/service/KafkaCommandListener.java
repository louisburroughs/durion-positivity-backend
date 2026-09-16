package com.positivity.workorder.internal.service;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.workorder.internal.dto.AssignmentUpdatedEvent;
import com.positivity.workorder.internal.enums.ResourceType;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * Kafka command listener for inbound workorder commands/events.
 *
 * <p>
 * Current supported command types:
 * <ul>
 * <li>{@code ASSIGNMENT_UPDATED}</li>
 * <li>{@code workorder.outbox.replay-requested} — consumer-initiated drift
 * repair (ADR-0044 §4):
 * re-queues published outbox events created at or after {@code payload.since}
 * for
 * re-publication; consumers dedupe by eventId so replay is idempotent.</li>
 * <li>{@code workorder.invoice.regenerate-requested} — async invoice
 * regeneration (ADR-0044 R4,
 * #842): generates the invoice draft for {@code payload.workorderId};
 * idempotent per workorder,
 * and the result flows back to callers via {@code invoice.events.v1}.</li>
 * <li>{@code workorder.fact-backfill.requested} — regenerate-from-state
 * seeding for workorders whose {@code workStartedAt}/{@code completedAt} predate those fields on
 * {@code WorkorderUpdatedV1} (issue #2021 AC8). Distinct from outbox replay, which can only re-send
 * facts already in {@code event_outbox}: a workorder that started before this contract slot existed
 * has outbox history with neither field, so replay alone cannot reach it. Optional
 * {@code payload.afterId} together with {@code payload.tenantId} resumes a bounded run from the
 * cursor a previous run logged; a cursor belongs to one tenant's id space, so both are needed.</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "workorder.kafka", name = "enabled", havingValue = "true")
public class KafkaCommandListener {

    private static final String PAYLOAD = "payload";

    private static final String RESOURCE_TYPE = "resourceType";
    private static final String COMMAND_ASSIGNMENT_UPDATED = "ASSIGNMENT_UPDATED";
    /**
     * Canonical dotted name normalized to command-type form:
     * WORKORDER_OUTBOX_REPLAY_REQUESTED.
     */
    private static final String COMMAND_OUTBOX_REPLAY_REQUESTED = "WORKORDER_OUTBOX_REPLAY_REQUESTED";

    /**
     * Canonical dotted name normalized: workorder.invoice.regenerate-requested
     * (#842).
     */
    private static final String COMMAND_INVOICE_REGENERATE_REQUESTED = "WORKORDER_INVOICE_REGENERATE_REQUESTED";

    /**
     * Canonical dotted name normalized: workorder.fact-backfill.requested (#2021 AC8).
     */
    private static final String COMMAND_FACT_BACKFILL_REQUESTED = "WORKORDER_FACT_BACKFILL_REQUESTED";

    /**
     * Covers the sub-millisecond skew between outbox createdAt and the eventId
     * timestamp.
     */
    private static final Duration REPLAY_WINDOW_SLACK = Duration.ofSeconds(1);

    /**
     * Replay commands older than this are rejected (PR #849 review — bound repair
     * cost).
     */
    @Value("${workorder.outbox.replay.max-lookback:P30D}")
    private Duration replayMaxLookback;

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final OutboxReplayService outboxReplayService;
    private final WorkorderInvoiceService workorderInvoiceService;
    private final WorkorderFactBackfillService workorderFactBackfillService;

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

            if (COMMAND_FACT_BACKFILL_REQUESTED.equals(commandType)) {
                handleFactBackfillRequested(root);
                return;
            }

            if (!COMMAND_ASSIGNMENT_UPDATED.equals(commandType)) {
                log.debug("Ignoring unsupported commandType={} message={}", commandType, message);
                return;
            }

            JsonNode payloadNode =
                    hasCommandType && root.has(PAYLOAD) && !root.get(PAYLOAD).isNull() ? root.get(PAYLOAD) : root;

            normalizeResourceType(payloadNode);
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

    /**
     * Applies the lenient {@code resourceType} rule to an inbound assignment command, in the one
     * place it is justified (#1656).
     *
     * <p>The rule itself is unchanged: the token is matched case-insensitively, and one that
     * matches nothing is warned about and removed so it is treated exactly like an omitted value —
     * {@link AssignmentUpdatedEvent#resolveResourceType()} then applies the documented {@code BAY}
     * fallback and the location, resource id and mechanics in the same payload still land. Strict
     * binding here would throw out of {@code treeToValue} into this method's log-and-swallow catch
     * and discard the entire assignment update silently.
     *
     * <p>It lives here, on the event path, rather than on {@link ResourceType} as a
     * {@code @JsonCreator}: a creator is global to the enum, so the same leniency also governed the
     * synchronous {@code operationalContext/override} REST body, where it turned a caller's typo
     * into a {@code 200} that pointed the workorder at a van while typing it as a bay. A
     * synchronous caller can be told it sent garbage; a Kafka producer cannot.
     *
     * @param eventNode the {@code AssignmentUpdatedEvent} node about to be bound; left untouched
     *     unless it carries an assignment payload with a {@code resourceType} field
     */
    private static void normalizeResourceType(@NonNull JsonNode eventNode) {
        if (!(eventNode.get(PAYLOAD) instanceof ObjectNode assignment)) {
            return;
        }
        JsonNode rawType = assignment.get(RESOURCE_TYPE);
        if (rawType == null || rawType.isNull()) {
            return;
        }
        ResourceType resolved = ResourceType.fromJson(rawType.stringValue(null));
        if (resolved == null) {
            assignment.remove(RESOURCE_TYPE);
        } else {
            assignment.put(RESOURCE_TYPE, resolved.name());
        }
    }

    private void handleInvoiceRegenerateRequested(@NonNull JsonNode root) {
        JsonNode payloadNode = root.get(PAYLOAD);
        String rawWorkorderId =
                payloadNode == null ? null : payloadNode.path("workorderId").stringValue(null);
        UUID workorderId;
        try {
            workorderId = UUID.fromString(rawWorkorderId);
        } catch (Exception _) {
            log.warn("Ignoring invoice regeneration command with missing/malformed workorderId: {}", root);
            return;
        }
        String idempotencyKey =
                payloadNode == null ? null : payloadNode.path("idempotencyKey").stringValue(null);
        try {
            // Idempotent per workorder: generateInvoice returns the existing invoice on
            // replay,
            // so command redelivery is harmless. Business failures (workorder missing or
            // not
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
            // PR #849 review: a malformed or ancient `since` must not trigger a huge
            // re-emit.
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
     * Re-emit current-state facts so a replica seeded before {@code workStartedAt}/{@code
     * completedAt} existed catches up (#2021 AC8).
     *
     * <p>Unbounded by time, unlike outbox replay's {@code max-lookback} guard: the whole point is to
     * reach workorders that predate the fields, so no time window could contain them all. The walk is
     * bounded by row count instead ({@link WorkorderFactBackfillService}), and the operation is
     * idempotent — a replica applies an equal version and skips a strictly-greater one, so re-running
     * repairs without duplicating.
     */
    private void handleFactBackfillRequested(@NonNull JsonNode root) {
        JsonNode payloadNode = root.get(PAYLOAD);
        UUID afterId = parseBackfillCursor(payloadNode, "afterId");
        UUID tenantId = parseBackfillCursor(payloadNode, "tenantId");
        WorkorderFactBackfillService.BackfillResult result =
                workorderFactBackfillService.backfillForCaller(afterId, tenantId);
        log.info(
                "Fact backfill command processed afterId={} tenantId={} published={} lastId={} more={}",
                afterId,
                tenantId,
                result.published(),
                result.lastId(),
                result.more());
        if (result.more()) {
            // A run stops at the configured bound so it cannot outlive max.poll.interval.ms and get
            // the consumer evicted. The operator re-sends the command with the tenant and cursor
            // logged here; the run is idempotent, so an overlapping resume is harmless. Both are
            // needed: a cursor belongs to one tenant's id space, so a resume without the tenant
            // would restart the fleet and never finish a tenant larger than one run's budget.
            log.warn(
                    "Fact backfill hit its per-run bound; re-send workorder.fact-backfill.requested "
                            + "with payload.tenantId={} and payload.afterId={} to continue",
                    result.tenantId(),
                    result.lastId());
        }
    }

    private @Nullable UUID parseBackfillCursor(@Nullable JsonNode payloadNode, String field) {
        String value = payloadNode == null ? null : payloadNode.path(field).stringValue(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException _) {
            // Resuming from the beginning is safe -- the run is idempotent -- but say so, because
            // silently restarting a large walk is not what the operator asked for.
            log.warn("Malformed payload.{}={} on fact backfill command; ignoring it", field, value);
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
