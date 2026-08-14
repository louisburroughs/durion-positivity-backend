package com.positivity.supplier.internal.command.service;

import com.positivity.domainevents.supplier.SupplierOrderRequestedV1;
import com.positivity.domainevents.supplier.SupplierPriceCatalogRepublishRequestedV1;
import com.positivity.supplier.internal.entity.ProcessedEvent;
import com.positivity.supplier.internal.order.service.TransmissionIntentWriter;
import com.positivity.supplier.internal.pricecatalog.service.PriceCatalogRepublisher;
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
 * The single consumer of {@code supplier.commands.v1} (ADR-0049 §3): purchase-order transmission
 * requests from pos-order, and PRICAT re-publication requests from pos-catalog (ADR-0044 §4).
 *
 * <h2>Why one consumer and not one per command</h2>
 *
 * {@code processed_events} is keyed by {@code event_id} alone, and every consumer here records
 * every event it sees — including the types it deliberately ignores, so that the owner's
 * reconciliation manifest counts the whole window rather than reporting ignored facts as missing.
 * Two consumer groups on this topic would therefore suppress each other: whichever group reached an
 * event first would record its id, and the group that actually handles that command would find the
 * id already present and skip the work entirely. The bug would be intermittent, would depend on
 * consumer scheduling, and would silently drop purchase orders or recoveries.
 *
 * <p>So the topic gets one consumer that dispatches by event type. Adding a third command type
 * means adding a branch here, not a listener elsewhere.
 *
 * <h2>This consumer does no network I/O</h2>
 *
 * Both branches record intent and stop — a transmission intent for the scheduler to dispatch, or
 * outbox rows for the publisher to drain. A vendor call or a broker send inside a Kafka transaction
 * is exactly the ambiguity ADR-0052 exists to prevent.
 *
 * <h2>Three failure modes, three different answers</h2>
 *
 * <ul>
 *   <li><strong>Transient database trouble</strong> — rethrown, so the container retries. Recording
 *       the command as processed here would lose a purchase order, or a recovery, permanently.
 *   <li><strong>A defect in the command itself</strong> — an unknown vendor alias, an import that
 *       was never staged — recorded as processed and logged at error. Retrying cannot fix it, and
 *       blocking the partition would stall every other tenant's commands behind one bad message.
 *   <li><strong>A repeat</strong> — a no-op. The event-id guard covers redelivery; beyond it, an
 *       order that already has an active intent is ignored by {@link TransmissionIntentWriter} and
 *       a re-publication inside its cooldown is refused by {@link PriceCatalogRepublisher}.
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.supplier.kafka", name = "enabled", havingValue = "true")
public class SupplierCommandListener {

    /** Producing domain of a purchase-order command, per the repo-wide {@code processed_events} convention. */
    static final String ORDER_OWNER = "order";

    /** Producing domain of a PRICAT re-publication request. */
    static final String CATALOG_OWNER = "catalog";

    /** Owner recorded for a command this module does not handle and whose source is unstated. */
    static final String UNKNOWN_OWNER = "unknown";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final TransmissionIntentWriter intentWriter;
    private final PriceCatalogRepublisher republisher;

    @KafkaListener(
            topics = "${pos.supplier.kafka.supplier-commands-topic:supplier.commands.v1}",
            groupId = "${pos.supplier.kafka.supplier-commands-consumer-group:pos-supplier-commands}")
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
            } else if (SupplierPriceCatalogRepublishRequestedV1.EVENT_TYPE.equals(eventType)) {
                applyRepublishRequested(envelope);
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
                .owner(ownerOf(eventType, envelope))
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

    private void applyRepublishRequested(JsonNode envelope) {
        SupplierPriceCatalogRepublishRequestedV1 request =
                objectMapper.treeToValue(envelope.path("payload"), SupplierPriceCatalogRepublishRequestedV1.class);
        republisher.republish(request);
    }

    /**
     * The producing domain, so a manifest scan keyed on owner reconciles against the right
     * producer. Commands this module ignores still get an owner from the envelope's source, because
     * "ignored" and "never delivered" have to stay distinguishable.
     */
    private static String ownerOf(String eventType, JsonNode envelope) {
        if (SupplierOrderRequestedV1.EVENT_TYPE.equals(eventType)) {
            return ORDER_OWNER;
        }
        if (SupplierPriceCatalogRepublishRequestedV1.EVENT_TYPE.equals(eventType)) {
            return CATALOG_OWNER;
        }
        String source = envelope.path("source").stringValue(null);
        if (source == null || source.isBlank()) {
            return UNKNOWN_OWNER;
        }
        return source.startsWith("pos-") ? source.substring("pos-".length()) : source;
    }
}
