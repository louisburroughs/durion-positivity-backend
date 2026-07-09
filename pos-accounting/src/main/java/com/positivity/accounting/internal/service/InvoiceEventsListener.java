package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.invoice.InvoiceUpdatedV1;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code invoice.events.v1} into accounting's {@code ext_invoice} replica (ADR-0044,
 * #842).
 *
 * <p>Same contract as {@link CustomerEventsListener}: idempotent via {@code processed_events} in
 * the upsert transaction, stale envelopes (aggregateVersion at or below the replica's) skipped,
 * transient DB errors rethrown for container retry/DLQ, malformed payloads logged and skipped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.accounting.kafka", name = "enabled", havingValue = "true")
public class InvoiceEventsListener {

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtInvoiceRepository extInvoiceRepository;

    @KafkaListener(
            topics = "${pos.accounting.kafka.invoice-events-topic:invoice.events.v1}",
            groupId = "pos-accounting-invoice-events")
    @Transactional
    public void onInvoiceEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable invoice event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        if (!InvoiceUpdatedV1.EVENT_TYPE.equals(eventType)) {
            log.debug("Ignoring invoice event type={}", eventType);
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping invoice event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate invoice event eventId={}", eventId);
            return;
        }

        try {
            applyInvoiceUpdate(envelope);
        } catch (TransientDataAccessException e) {
            // Retry with backoff / DLQ via the container error handler (ADR-0044 §4).
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed invoice event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyInvoiceUpdate(JsonNode envelope) {
        InvoiceUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), InvoiceUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        UUID invoiceId = payload.invoiceId();

        ExtInvoice existing = extInvoiceRepository.findById(invoiceId).orElse(null);
        if (existing != null && existing.getAggregateVersion() >= aggregateVersion && aggregateVersion > 0) {
            log.debug(
                    "Skipping stale invoice event invoiceId={} eventVersion={} replicaVersion={}",
                    invoiceId,
                    aggregateVersion,
                    existing.getAggregateVersion());
            return;
        }

        extInvoiceRepository.save(ExtInvoice.builder()
                .invoiceId(invoiceId)
                .invoiceNumber(payload.invoiceNumber())
                .workorderId(payload.workorderId())
                .estimateId(payload.estimateId())
                .locationId(payload.locationId())
                .partyId(payload.partyId())
                .status(payload.status())
                .subtotal(payload.subtotal())
                .tax(payload.tax())
                .total(payload.total())
                .adjustmentsAmount(payload.adjustmentsAmount())
                .invoiceCreatedAt(payload.createdAt())
                .finalizedAt(payload.finalizedAt())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info(
                "Updated ext_invoice replica invoiceId={} status={} version={}",
                invoiceId,
                payload.status(),
                aggregateVersion);
    }
}
