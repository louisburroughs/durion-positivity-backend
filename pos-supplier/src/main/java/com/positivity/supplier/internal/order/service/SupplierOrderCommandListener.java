package com.positivity.supplier.internal.order.service;

import com.positivity.domainevents.supplier.SupplierOrderRequestedV1;
import com.positivity.supplier.internal.entity.ProcessedEvent;
import com.positivity.supplier.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code supplier.commands.v1} and turns {@code supplier.order.requested} into a
 * transmission intent (ADR-0049 §3, ADR-0052 §1/§2).
 *
 * <h2>This consumer does no network I/O</h2>
 *
 * It records intent and stops. Dispatch is a separate, restartable step driven by
 * {@link OrderTransmissionScheduler} off the persisted queue. Sending inside the consumer would
 * put a vendor call inside a Kafka transaction, where a rebalance or a rollback after a
 * successful send is exactly the ambiguity ADR-0052 exists to prevent.
 *
 * <h2>Three failure modes, three different answers</h2>
 *
 * <ul>
 *   <li><strong>Transient database trouble</strong> — rethrown, so the container retries.
 *       Recording the command as processed here would lose a purchase order permanently.
 *   <li><strong>Unknown vendor alias</strong> — recorded as processed and logged at error. It is a
 *       producer defect that retrying cannot fix, and blocking the partition on it would stall
 *       every other vendor's orders behind one bad command.
 *   <li><strong>Repeat of an order that already has an active intent</strong> — a no-op, by
 *       design. See {@link TransmissionIntentWriter}.
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.supplier.kafka", name = "enabled", havingValue = "true")
public class SupplierOrderCommandListener {

    /** Producing domain, per the repo-wide {@code processed_events} convention. */
    static final String OWNER = "order";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final TransmissionIntentWriter intentWriter;

    @KafkaListener(
            topics = "${pos.supplier.kafka.supplier-commands-topic:supplier.commands.v1}",
            groupId = "${pos.supplier.kafka.supplier-commands-consumer-group:pos-supplier-order-commands}")
    @Transactional
    public void onSupplierCommand(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable supplier command", e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping supplier command without eventId");
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            if (SupplierOrderRequestedV1.EVENT_TYPE.equals(eventType)) {
                applyOrderRequested(envelope, eventId);
            } else {
                log.debug("Ignoring supplier command type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException | DataIntegrityViolationException e) {
            // Rethrown so the container retries. A constraint violation here is the active-intent
            // unique index doing its job under a race between two instances; the retry finds the
            // winner's row and treats the command as the repeat it is.
            throw e;
        } catch (TransmissionIntentWriter.UnknownSupplierException e) {
            log.error("Supplier order command eventId={} names an unusable vendor: {}", eventId, e.getMessage());
        } catch (Exception e) {
            log.warn("Skipping malformed supplier command eventId={}", eventId, e);
        }

        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyOrderRequested(JsonNode envelope, String eventId) {
        SupplierOrderRequestedV1 command =
                objectMapper.treeToValue(envelope.path("payload"), SupplierOrderRequestedV1.class);
        if (command.lines().isEmpty()) {
            log.warn("Supplier order command eventId={} carries no lines; nothing to transmit", eventId);
            return;
        }
        String correlationId = envelope.path("correlationId").stringValue(eventId);
        intentWriter.mint(command, correlationId);
    }
}
