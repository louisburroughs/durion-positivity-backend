package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.payment.SettlementProviderConfigV1;
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
 * Consumes the compacted {@code payment.settlement-config.v1} topic into accounting's read-only
 * {@code ext_payment_settlement_config} replica (ADR-0044 §3, story F1c, decision D-9).
 *
 * <p>Each record is the full current config for its {@code providerCode} (compaction key,
 * last-writer-wins), so ingest is a plain upsert. Idempotent via {@code processed_events} on the
 * envelope eventId; transient DB errors rethrown for retry/DLQ, malformed payloads skipped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.accounting.kafka", name = "enabled", havingValue = "true")
public class SettlementConfigEventsListener {

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final SettlementConfigService settlementConfigService;

    @KafkaListener(
            topics = "${pos.accounting.kafka.settlement-config-topic:payment.settlement-config.v1}",
            groupId = "pos-accounting-settlement-config")
    @Transactional
    public void onSettlementConfigEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable settlement config event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        if (!SettlementProviderConfigV1.EVENT_TYPE.equals(eventType)) {
            log.debug("Ignoring settlement config event type={}", eventType);
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping settlement config event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate settlement config event eventId={}", eventId);
            return;
        }

        // Deserialization/validation failures are terminal for this record — skip and mark processed
        // so a malformed payload does not poison the partition. Anything thrown by the upsert
        // (transient DB errors, unexpected failures) propagates for container retry / DLQ (finding 13).
        SettlementProviderConfigV1 payload;
        try {
            payload = objectMapper.treeToValue(envelope.path("payload"), SettlementProviderConfigV1.class);
        } catch (Exception e) {
            log.warn("Skipping malformed settlement config event eventId={}", eventId, e);
            markProcessed(eventId);
            return;
        }

        settlementConfigService.upsert(payload);
        markProcessed(eventId);
    }

    private void markProcessed(@NonNull String eventId) {
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .processedAt(Instant.now(clock))
                .build());
    }
}
