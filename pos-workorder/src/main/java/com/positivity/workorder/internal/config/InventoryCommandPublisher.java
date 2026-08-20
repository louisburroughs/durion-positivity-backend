package com.positivity.workorder.internal.config;

import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
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
    public static final String RESERVATION_REQUEST_COMMAND_TYPE = "inventory.reservation.request-requested";

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

    /**
     * Register demand for one workorder part at the servicing site (CAP #1315).
     *
     * <p>Issued alongside the replica-backed gate, not instead of it: the gate decides what the
     * technician is told now, this command asks the owner to decide against its own ledger. The
     * outcome fact carries that answer back and corrects the part if the two disagree.
     *
     * <p>The command id is derived from (part, item, quantity), so re-issuing the same part
     * collapses to one command under pos-inventory's dedupe while a different quantity is a new
     * command against the same reservation.
     *
     * @param uomCode the unit {@code requiredQuantity} is expressed in (ADR-0055 stage 3, #1415);
     *     {@code null} means the product's base unit. pos-inventory converts to base itself, using
     *     {@code DOWN} rounding so the reservation never promises more than exists.
     */
    public void requestReservation(
            @NonNull UUID workorderLineId,
            @NonNull UUID stockItemId,
            @NonNull BigDecimal requiredQuantity,
            @NonNull UUID locationId,
            @Nullable String uomCode) {
        UUID commandId = deterministicCommandId(
                RESERVATION_REQUEST_COMMAND_TYPE,
                workorderLineId + ":" + stockItemId + ":" + normalizedQuantityKey(requiredQuantity) + ":" + uomCode);
        ReservationRequestPayload payload =
                new ReservationRequestPayload(workorderLineId, stockItemId, requiredQuantity, locationId, uomCode);
        send(RESERVATION_REQUEST_COMMAND_TYPE, commandId, workorderLineId.toString(), payload);
        log.info("Queued reservation request command {} for workorder line {}", commandId, workorderLineId);
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

    /**
     * The quantity as it appears in the idempotency key.
     *
     * <p>{@code stripTrailingZeros} first: since ADR-0055 (#1414) the quantity is a
     * {@link BigDecimal}, and {@code 2} and {@code 2.00} are the same demand spelled two ways. A
     * client retry that serialises the number differently must still collapse to the same command
     * under pos-inventory's dedupe, which keying on the raw {@code toString} would not do.
     */
    private static String normalizedQuantityKey(@NonNull BigDecimal quantity) {
        return quantity.stripTrailingZeros().toPlainString();
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

    /**
     * Matches the fields pos-inventory's {@code handleReservationRequestRequested} reads.
     * {@code uomCode} is {@code null} for the product's base unit (ADR-0055 stage 3, #1415).
     */
    record ReservationRequestPayload(
            @NonNull UUID workorderLineId,
            @NonNull UUID stockItemId,
            @NonNull BigDecimal requiredQuantity,
            @NonNull UUID locationId,
            @Nullable String uomCode) {}

    record ConsumePayload(
            @NonNull UUID workorderId,
            @Nullable UUID pickListId,
            @NonNull List<ConsumeLine> items) {}
}
