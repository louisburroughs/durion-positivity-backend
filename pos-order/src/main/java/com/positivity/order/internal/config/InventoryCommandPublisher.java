package com.positivity.order.internal.config;

import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
 * Publishes reservation requests to {@code inventory.commands.v1} (ADR-0044 §4, CAP #1315).
 *
 * <p>Checkout registers demand for each line this way. pos-inventory decides the outcome against
 * its own ledger — soft reservation when covered, backorder when short — and reports it back as
 * an {@code inventory.reservation.outcome.recorded} fact.
 *
 * <p>Command ids are derived from (line, sku, quantity), so a checkout retry collapses to one
 * command under pos-inventory's {@code processed_events} dedupe, while a genuine quantity change
 * is a new command that updates the same reservation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.order.kafka", name = "enabled", havingValue = "true")
public class InventoryCommandPublisher {

    public static final String RESERVATION_REQUEST_COMMAND_TYPE = "inventory.reservation.request-requested";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${pos.order.kafka.inventory-commands-topic:inventory.commands.v1}")
    private String inventoryCommandsTopic;

    @Value("${pos.order.kafka.command-send-timeout-ms:10000}")
    private long sendTimeoutMs;

    /**
     * Register demand for one sales-order line at the selling location.
     *
     * @param salesOrderLineId demand line the reservation is for
     * @param stockItemId stock item requested
     * @param requiredQuantity quantity requested; decimal-capable per the product's catalog
     *     divisibility declaration (ADR-0055, #1414)
     * @param locationId selling location the demand must be covered at
     * @param uomCode unit {@code requiredQuantity} is expressed in (ADR-0055 stage 3, #1415);
     *     {@code null} means the product's base unit. Sales-order lines stay integral by decision
     *     (ADR-0055 leaves {@code SalesOrderLine.quantity} an {@code int}), so every caller sends
     *     {@code null} today; the parameter exists so the command shape matches pos-workorder's
     *     and pos-inventory's single {@code handleReservationRequestRequested} reads one contract.
     */
    public void requestReservation(
            @NonNull UUID salesOrderLineId,
            @NonNull UUID stockItemId,
            @NonNull BigDecimal requiredQuantity,
            @NonNull UUID locationId,
            @Nullable String uomCode) {
        String normalizedUomCode = normalizeUomCode(uomCode);
        String key = salesOrderLineId + ":" + stockItemId + ":" + normalizedQuantityKey(requiredQuantity)
                + (normalizedUomCode != null ? ":" + normalizedUomCode : "");
        UUID commandId = deterministicCommandId(RESERVATION_REQUEST_COMMAND_TYPE, key);
        ReservationRequestPayload payload = new ReservationRequestPayload(
                salesOrderLineId, stockItemId, requiredQuantity, locationId, normalizedUomCode);
        send(RESERVATION_REQUEST_COMMAND_TYPE, commandId, salesOrderLineId.toString(), payload);
        log.info("Queued reservation request command {} for sales-order line {}", commandId, salesOrderLineId);
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

    /**
     * Trims {@code uomCode} and collapses blank to {@code null} before it reaches either the
     * idempotency key or the payload. Appending it to the key unconditionally — including when
     * null, which string concatenation renders as the literal {@code "null"} — would change the
     * deterministic command id for every base-unit command that existed before uomCode was added
     * to this payload (ADR-0055 stage 3, #1415), breaking retry dedupe across the change. Only a
     * real code gets suffixed onto the key; null or blank keeps the pre-#1415 id shape.
     */
    @Nullable
    private static String normalizeUomCode(@Nullable String uomCode) {
        if (uomCode == null) {
            return null;
        }
        String trimmed = uomCode.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

    /**
     * Matches the fields pos-inventory's {@code handleReservationRequestRequested} reads.
     * {@code uomCode} is {@code null} for the product's base unit (ADR-0055 stage 3, #1415).
     */
    record ReservationRequestPayload(
            @NonNull UUID salesOrderLineId,
            @NonNull UUID stockItemId,
            @NonNull BigDecimal requiredQuantity,
            @NonNull UUID locationId,
            @Nullable String uomCode) {}
}
