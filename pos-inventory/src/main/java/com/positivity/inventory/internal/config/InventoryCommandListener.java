package com.positivity.inventory.internal.config;

import com.positivity.inventory.internal.dto.consumption.ConsumeItemLine;
import com.positivity.inventory.internal.dto.consumption.ConsumeItemsRequest;
import com.positivity.inventory.internal.entity.ProcessedEvent;
import com.positivity.inventory.internal.repository.ProcessedEventRepository;
import com.positivity.inventory.service.ConsumptionService;
import com.positivity.inventory.service.OutboxReplayService;
import com.positivity.inventory.service.PickListService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
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
 * Kafka command listener for {@code inventory.commands.v1} (ADR-0044 §4, issue #899).
 *
 * <p>Supported command types:
 * <ul>
 * <li>{@code inventory.outbox.replay-requested} — consumer-initiated drift repair and replica
 * bootstrap: re-queues published outbox events created in the requested window for
 * re-publication; consumers dedupe by eventId so replay is idempotent.</li>
 * <li>{@code inventory.pick-task.confirm-requested} — async pick confirmation (#901, replaces
 * pos-workorder's synchronous {@code InventoryPickClient.confirmPickTask}); deduplicated via
 * {@code processed_events} (owner {@code inventory-commands}), the resulting pick-task/pick-list
 * facts on {@code inventory.events.v1} resolve the consumer's pending state.</li>
 * <li>{@code inventory.items.consume-requested} — async consumption of picked items (#901,
 * replaces {@code InventoryPickClient.consumePickedItems}); same idempotency, the
 * {@code inventory.consumption.recorded} fact carries the result.</li>
 * <li>{@code inventory.pick-list.release-requested} — async pick-list release (#901).</li>
 * </ul>
 *
 * <p>Business validation failures (scan mismatch, not-picked, over-consumption) are permanent:
 * they are logged, the command id is still recorded, and no fact is emitted — the consumer's
 * pending state surfaces through its timeout/attention path, not through HTTP errors.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.inventory.kafka", name = "enabled", havingValue = "true")
public class InventoryCommandListener {

    /** Canonical dotted name normalized to command-type form: INVENTORY_OUTBOX_REPLAY_REQUESTED. */
    private static final String COMMAND_OUTBOX_REPLAY_REQUESTED = "INVENTORY_OUTBOX_REPLAY_REQUESTED";

    /** Canonical dotted name normalized: inventory.pick-list.release-requested. */
    private static final String COMMAND_PICK_LIST_RELEASE_REQUESTED = "INVENTORY_PICK_LIST_RELEASE_REQUESTED";

    /** Canonical dotted name normalized: inventory.pick-task.confirm-requested. */
    private static final String COMMAND_PICK_TASK_CONFIRM_REQUESTED = "INVENTORY_PICK_TASK_CONFIRM_REQUESTED";

    /** Canonical dotted name normalized: inventory.items.consume-requested. */
    private static final String COMMAND_ITEMS_CONSUME_REQUESTED = "INVENTORY_ITEMS_CONSUME_REQUESTED";

    static final String COMMANDS_OWNER = "inventory-commands";

    /** Covers the sub-millisecond skew between outbox createdAt and the eventId timestamp. */
    private static final Duration REPLAY_WINDOW_SLACK = Duration.ofSeconds(1);

    /** Replay commands older than this are rejected — bounds repair cost. */
    @Value("${pos.inventory.outbox.replay.max-lookback:P30D}")
    private Duration replayMaxLookback;

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final OutboxReplayService outboxReplayService;
    private final PickListService pickListService;
    private final ConsumptionService consumptionService;
    private final ProcessedEventRepository processedEventRepository;

    @KafkaListener(
            topics = "${pos.inventory.kafka.commands-topic:inventory.commands.v1}",
            groupId = "${pos.inventory.kafka.commands-consumer-group:pos-inventory-commands}")
    @Transactional
    public void onCommand(@NonNull String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String rawCommandType = root.path("commandType").stringValue(null);
            if (rawCommandType == null || rawCommandType.isBlank()) {
                log.debug("Ignoring command without commandType: {}", message);
                return;
            }
            // Normalize dotted command names (inventory.outbox.replay-requested) to one form.
            String commandType =
                    rawCommandType.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');

            if (COMMAND_OUTBOX_REPLAY_REQUESTED.equals(commandType)) {
                handleOutboxReplayRequested(root);
                return;
            }
            if (COMMAND_PICK_LIST_RELEASE_REQUESTED.equals(commandType)) {
                handleDeduplicated(root, this::handlePickListReleaseRequested);
                return;
            }
            if (COMMAND_PICK_TASK_CONFIRM_REQUESTED.equals(commandType)) {
                handleDeduplicated(root, this::handlePickTaskConfirmRequested);
                return;
            }
            if (COMMAND_ITEMS_CONSUME_REQUESTED.equals(commandType)) {
                handleDeduplicated(root, this::handleItemsConsumeRequested);
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

    /** Command-id dedupe shared by the pick commands: at-most-once per commandId. */
    private void handleDeduplicated(@NonNull JsonNode root, @NonNull Consumer<JsonNode> handler) {
        String commandId = root.path("commandId").stringValue(null);
        if (commandId == null || commandId.isBlank()) {
            log.warn("Ignoring pick command without commandId: {}", root);
            return;
        }
        if (processedEventRepository.existsById(commandId)) {
            log.debug("Skipping duplicate pick command commandId={}", commandId);
            return;
        }
        try {
            handler.accept(root.path("payload"));
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            // Permanent business failure (mismatch, not-picked, unknown id): record the command
            // as processed so redelivery does not retry it; no fact is emitted, the consumer's
            // pending state surfaces via its timeout/attention path (#901).
            log.warn("Pick command {} failed permanently: {}", commandId, e.getMessage(), e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(commandId)
                .owner(COMMANDS_OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void handlePickListReleaseRequested(@NonNull JsonNode payload) {
        UUID pickListId = parseUuid(payload, "pickListId");
        if (pickListId == null) {
            log.warn("Ignoring pick-list release command with missing pickListId: {}", payload);
            return;
        }
        var response = pickListService.releasePickList(pickListId);
        log.info("Pick list release command processed pickListId={} status={}", pickListId, response.getStatus());
    }

    private void handlePickTaskConfirmRequested(@NonNull JsonNode payload) {
        UUID pickListId = parseUuid(payload, "pickListId");
        UUID pickTaskId = parseUuid(payload, "pickTaskId");
        UUID scannedSkuId = parseUuid(payload, "scannedSkuId");
        UUID scannedLocationId = parseUuid(payload, "scannedLocationId");
        int quantityPicked = payload.path("quantityPicked").intValue(0);
        if (pickListId == null || pickTaskId == null || scannedSkuId == null || scannedLocationId == null) {
            log.warn("Ignoring pick-task confirm command with missing identifiers: {}", payload);
            return;
        }
        var response = pickListService.confirmPickTask(
                pickListId, pickTaskId, scannedSkuId, scannedLocationId, quantityPicked);
        log.info(
                "Pick task confirm command processed pickListId={} pickTaskId={} status={}",
                pickListId,
                pickTaskId,
                response.getStatus());
    }

    private void handleItemsConsumeRequested(@NonNull JsonNode payload) {
        UUID workorderId = parseUuid(payload, "workorderId");
        if (workorderId == null) {
            log.warn("Ignoring consume command with missing workorderId: {}", payload);
            return;
        }
        UUID pickListId = parseUuid(payload, "pickListId");
        List<ConsumeItemLine> items = new ArrayList<>();
        for (JsonNode line : payload.path("items")) {
            UUID pickTaskId = parseUuid(line, "pickTaskId");
            if (pickTaskId == null) {
                continue;
            }
            ConsumeItemLine item = new ConsumeItemLine();
            item.setPickTaskId(pickTaskId);
            item.setSkuId(parseUuid(line, "skuId"));
            item.setQuantity(line.path("quantity").intValue(0));
            items.add(item);
        }
        ConsumeItemsRequest request = new ConsumeItemsRequest();
        request.setWorkorderId(workorderId);
        request.setPickListId(pickListId);
        request.setItems(items);
        var response = consumptionService.consumePickedItems(request);
        log.info(
                "Consume command processed workorderId={} pickListId={} totalItemsConsumed={}",
                workorderId,
                pickListId,
                response.getTotalItemsConsumed());
    }

    private @Nullable UUID parseUuid(@Nullable JsonNode node, @NonNull String field) {
        String value = node == null ? null : node.path(field).stringValue(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException _) {
            log.warn("Malformed UUID {}={}", field, value);
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
