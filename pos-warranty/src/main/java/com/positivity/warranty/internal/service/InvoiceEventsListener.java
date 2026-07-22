package com.positivity.warranty.internal.service;

import com.positivity.domainevents.invoice.InvoiceUpdatedV1;
import com.positivity.warranty.internal.entity.ExtInvoiceLineReplica;
import com.positivity.warranty.internal.entity.ExtInvoiceReplica;
import com.positivity.warranty.internal.entity.ProcessedEvent;
import com.positivity.warranty.internal.repository.ExtInvoiceLineReplicaRepository;
import com.positivity.warranty.internal.repository.ExtInvoiceReplicaRepository;
import com.positivity.warranty.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 * Consumes {@code invoice.events.v1} into the {@code ext_invoice} + {@code ext_invoice_line}
 * replicas (ADR-0044 §6, #924) — the event-fed replacement for the retired synchronous
 * {@code InvoiceClient.searchInvoiceLines} read used by candidate-line origin search. Settlement
 * writes and reconciliation reads still use the sync InvoiceClient pending a further ADR amendment.
 *
 * <p>Consumer contract mirrors the module's other listeners: {@code processed_events} idempotency
 * (owner {@code invoice}) in the apply transaction, strictly-below stale guard on the fact's JPA
 * optimistic-lock {@code aggregateVersion}, transient DB errors rethrown for container retry/DLQ.
 * Unsupported event types still record their eventIds so the owner's manifest reconciles.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.warranty.kafka", name = "enabled", havingValue = "true")
public class InvoiceEventsListener {

    /** Producing domain, per the repo-wide processed_events convention (manifest scans key on it). */
    static final String OWNER = "invoice";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtInvoiceReplicaRepository extInvoiceReplicaRepository;
    private final ExtInvoiceLineReplicaRepository extInvoiceLineReplicaRepository;

    @KafkaListener(
            topics = "${pos.warranty.kafka.invoice-events-topic:invoice.events.v1}",
            groupId = "${pos.warranty.kafka.invoice-events-consumer-group:pos-warranty-invoice-events}")
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
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping invoice event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            if (InvoiceUpdatedV1.EVENT_TYPE.equals(eventType)) {
                InvoiceUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), InvoiceUpdatedV1.class);
                long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
                applyInvoiceReplica(payload, aggregateVersion);
            } else {
                // Ignored types still fall through to the processed_events insert below: the
                // owner's manifest counts every fact in the window.
                log.debug("Ignoring invoice event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed invoice event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyInvoiceReplica(@NonNull InvoiceUpdatedV1 payload, long aggregateVersion) {
        ExtInvoiceReplica existing =
                extInvoiceReplicaRepository.findById(payload.invoiceId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        extInvoiceReplicaRepository.save(ExtInvoiceReplica.builder()
                .invoiceId(payload.invoiceId())
                .invoiceNumber(payload.invoiceNumber())
                .partyId(payload.partyId())
                .status(payload.status())
                .invoiceCreatedAt(payload.createdAt())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());

        extInvoiceLineReplicaRepository.deleteByInvoiceId(payload.invoiceId());
        if (payload.lines() != null) {
            List<ExtInvoiceLineReplica> lines = new ArrayList<>();
            for (InvoiceUpdatedV1.InvoiceLine line : payload.lines()) {
                lines.add(ExtInvoiceLineReplica.builder()
                        .invoiceItemId(line.invoiceItemId())
                        .invoiceId(payload.invoiceId())
                        .description(line.description())
                        .quantity(line.quantity())
                        .unitPrice(line.unitPrice())
                        .amount(line.amount())
                        .workorderItemId(line.workorderItemId())
                        .itemType(line.itemType())
                        .build());
            }
            extInvoiceLineReplicaRepository.saveAll(lines);
        }
        log.info("Updated ext_invoice invoiceId={} version={}", payload.invoiceId(), aggregateVersion);
    }
}
