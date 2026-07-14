package com.positivity.workorder.internal.config;

import com.positivity.shared.id.UUIDv7Generator;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes pick-workflow commands to {@code inventory.commands.v1} (ADR-0044 §4, issue #901
 * Phase 5.5) — the async replacement for the retired synchronous {@code InventoryPickClient}
 * write calls ({@code confirmPickTask}, {@code consumePickedItems}).
 *
 * <p>The send waits for the broker ack so callers get an accepted/failed signal; pos-inventory
 * applies the command and the resulting pick facts flow back through
 * {@code inventory.events.v1} into the pick replicas, resolving the pending state.
 *
 * <p>Command ids are derived deterministically from the operation's natural idempotency key, so
 * client retries collapse to one command and pos-inventory's {@code processed_events} dedupe
 * applies each at most once.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "workorder.kafka", name = "enabled", havingValue = "true")
public class InventoryCommandPublisher {

    public static final String CONFIRM_COMMAND_TYPE = "inventory.pick-task.confirm-requested";
    public static final String CONSUME_COMMAND_TYPE = "inventory.items.consume-requested";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${workorder.kafka.inventory-commands-topic:inventory.commands.v1}")
    private String inventoryCommandsTopic;

    @Value("${workorder.kafka.command-send-timeout-ms:10000}")
    private long sendTimeoutMs;

    /**
     * Queue a pick-task confirmation. The command id is derived from
     * (task, quantity) so a client retry of the same confirmation collapses to one command,
     * while a genuine re-confirmation with a different quantity is a new command.
     */
    public void requestPickTaskConfirm(
            @NonNull UUID pickListId,
            @NonNull UUID pickTaskId,
            @NonNull UUID scannedSkuId,
            @NonNull UUID scannedLocationId,
            int quantityPicked) {
        UUID commandId = deterministicCommandId(
                CONFIRM_COMMAND_TYPE, pickTaskId + ":" + scannedSkuId + ":" + scannedLocationId + ":" + quantityPicked);
        ConfirmPayload payload =
                new ConfirmPayload(pickListId, pickTaskId, scannedSkuId, scannedLocationId, quantityPicked);
        send(CONFIRM_COMMAND_TYPE, commandId, pickListId.toString(), payload);
        log.info("Queued pick-task confirm command {} for task {}", commandId, pickTaskId);
    }

    /**
     * Queue consumption of picked items. The command id is derived from the full line set so a
     * client retry collapses to one command; distinct consumptions are distinct commands.
     */
    public void requestItemsConsume(
            @NonNull UUID workorderId, @Nullable UUID pickListId, @NonNull List<ConsumeLine> items) {
        // Sort by pickTaskId so the key is independent of the client's line ordering: a retry
        // with the same lines in a different order must collapse to the same command id.
        StringBuilder key = new StringBuilder(workorderId.toString());
        items.stream()
                .sorted(java.util.Comparator.comparing(ConsumeLine::pickTaskId))
                .forEach(item ->
                        key.append('|').append(item.pickTaskId()).append(':').append(item.quantity()));
        UUID commandId = deterministicCommandId(CONSUME_COMMAND_TYPE, key.toString());
        ConsumePayload payload = new ConsumePayload(workorderId, pickListId, items);
        send(CONSUME_COMMAND_TYPE, commandId, workorderId.toString(), payload);
        log.info("Queued items consume command {} for workorder {}", commandId, workorderId);
    }

    private void send(@NonNull String commandType, @NonNull UUID commandId, @NonNull String key, Object payload) {
        try {
            String command = objectMapper.writeValueAsString(new Command(commandId.toString(), commandType, payload));
            kafkaTemplate.send(inventoryCommandsTopic, key, command).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing " + commandType + " command", e);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to publish " + commandType + " command " + commandId, e);
        }
    }

    private static UUID deterministicCommandId(@NonNull String commandType, @NonNull String idempotencyKey) {
        if (idempotencyKey.isBlank()) {
            return UUIDv7Generator.generate();
        }
        return UUID.nameUUIDFromBytes((commandType + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
    }

    /** Command envelope for pos-inventory's {@code inventory.commands.v1} listener. */
    record Command(
            @NonNull String commandId,
            @NonNull String commandType,
            @NonNull Object payload) {}

    record ConfirmPayload(
            @NonNull UUID pickListId,
            @NonNull UUID pickTaskId,
            @NonNull UUID scannedSkuId,
            @NonNull UUID scannedLocationId,
            int quantityPicked) {}

    /** One consumed line; matches pos-inventory's {@code ConsumeItemLine}. */
    public record ConsumeLine(
            @NonNull UUID pickTaskId, @Nullable UUID skuId, int quantity) {}

    record ConsumePayload(
            @NonNull UUID workorderId,
            @Nullable UUID pickListId,
            @NonNull List<ConsumeLine> items) {}
}
