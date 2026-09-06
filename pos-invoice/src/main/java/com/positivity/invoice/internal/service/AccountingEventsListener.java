package com.positivity.invoice.internal.service;

import com.positivity.domainevents.accounting.InvoiceGlPostedV1;
import com.positivity.invoice.internal.entity.ProcessedEvent;
import com.positivity.invoice.internal.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code accounting.events.v1} to close the GL-posting loop (ADR-0044 §6, #1843).
 *
 * <p>Finalizing an invoice emits {@code invoice.invoice.updated} (status FINALIZED);
 * pos-accounting posts the revenue journal entry for it and answers with
 * {@code accounting.invoice.gl-posted}. Only that fact is handled here: a {@code POSTED} fact
 * moves the invoice {@code FINALIZED -> POSTED} and records the journal entry id (see
 * {@link InvoiceFinalizationService#markPosted}); a {@code REVERSED} fact changes nothing in
 * pos-invoice — the invoice is already DRAFT or CANCELLED, which is what caused the reversal.
 * Other accounting facts on the topic are other consumers' concerns.
 *
 * <p>Idempotent via {@code processed_events}; transient database errors are rethrown for
 * retry/DLQ; malformed payloads are logged, counted, and dropped without wedging the partition.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.invoice.kafka", name = "enabled", havingValue = "true")
public class AccountingEventsListener {

    static final String OWNER = "accounting";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final InvoiceFinalizationService invoiceFinalizationService;
    private final Counter payloadRejectedCounter;

    public AccountingEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            InvoiceFinalizationService invoiceFinalizationService,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.invoiceFinalizationService = invoiceFinalizationService;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", OWNER)
                        .tag("entity", "accounting-events")
                        .register(registry);
    }

    @KafkaListener(
            topics = "${pos.invoice.kafka.accounting-events-topic:accounting.events.v1}",
            groupId = "${pos.invoice.kafka.accounting-events-consumer-group:pos-invoice-accounting-events}")
    @Transactional
    public void onAccountingEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable accounting event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping accounting event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            switch (eventType == null ? "" : eventType) {
                case InvoiceGlPostedV1.EVENT_TYPE -> applyGlPosted(envelope);
                // Ignored types still fall through to the processed_events insert below, mirroring
                // the replica listeners: the dedup row is per fact on the topic, not per handled fact.
                default -> log.debug("Ignoring accounting event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed accounting event payload eventId={}: {}", eventId, e.getMessage(), e);
        } catch (Exception e) {
            log.warn("Skipping malformed accounting event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyGlPosted(JsonNode envelope) {
        InvoiceGlPostedV1 payload = objectMapper.treeToValue(envelope.path("payload"), InvoiceGlPostedV1.class);
        switch (payload.postingKind()) {
            case POSTED ->
                invoiceFinalizationService.markPosted(
                        payload.invoiceId(), payload.journalEntryId(), payload.finalizedAt());
            // The invoice already left FINALIZED (revert or cancel) — that is what accounting is
            // reversing. Nothing to transition; the reversal is recorded on the ledger side only.
            case REVERSED ->
                log.info(
                        "GL reversal recorded by accounting for invoiceId={} journalEntryId={} reverses={}",
                        payload.invoiceId(),
                        payload.journalEntryId(),
                        payload.reversedJournalEntryId());
        }
    }
}
