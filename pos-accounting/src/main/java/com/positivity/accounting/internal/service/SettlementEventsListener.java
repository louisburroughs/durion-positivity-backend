package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.service.SettlementReconciliationService;
import com.positivity.domainevents.payment.SettlementReportedV1;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code payment.events.v1} settlement facts into accounting's settlement reconciliation
 * (story F1c, issue #963, decisions D-9…D-14).
 *
 * <p>Same reliability contract as {@link InvoiceEventsListener}: idempotent via {@code
 * processed_events} in the ingest transaction, transient DB errors rethrown for container
 * retry/DLQ, malformed payloads logged and skipped. Only {@code payment.settlement.reported} events
 * are handled; other event types on the topic are ignored.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.accounting.kafka", name = "enabled", havingValue = "true")
public class SettlementEventsListener {

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final SettlementReconciliationService reconciliationService;

    @KafkaListener(
            topics = "${pos.accounting.kafka.payment-events-topic:payment.events.v1}",
            groupId = "pos-accounting-settlement-events")
    @Transactional
    public void onPaymentEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable payment event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        if (!SettlementReportedV1.EVENT_TYPE.equals(eventType)) {
            log.debug("Ignoring payment event type={}", eventType);
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping settlement event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate settlement event eventId={}", eventId);
            return;
        }

        // Only deserialization/validation failures are terminal for this record — skip and mark
        // processed so a malformed payload does not poison the partition. Anything thrown by
        // ingest (transient DB errors and unexpected failures alike) propagates unwrapped for
        // container retry / DLQ, rather than being silently swallowed and marked processed
        // (finding 13).
        SettlementReportedV1 payload;
        try {
            payload = objectMapper.treeToValue(envelope.path("payload"), SettlementReportedV1.class);
        } catch (Exception e) {
            log.warn("Skipping malformed settlement event eventId={}", eventId, e);
            markProcessed(eventId);
            return;
        }

        reconciliationService.ingestSettlement(payload);
        markProcessed(eventId);
    }

    private void markProcessed(@NonNull String eventId) {
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .processedAt(Instant.now(clock))
                .build());
    }
}
