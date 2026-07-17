package com.positivity.warranty.internal.service;

import com.positivity.domainevents.workorder.WorkorderUpdatedV1;
import com.positivity.warranty.internal.entity.ProcessedEvent;
import com.positivity.warranty.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
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
 * Consumes {@code workorder.events.v1} for sale-driven warranty auto-registration (issue #925),
 * mirroring the pos-invoice {@code WorkorderEventsListener} consumer contract:
 * {@code processed_events} idempotency in the apply transaction, transient DB errors rethrown
 * for container retry/DLQ, everything else logged and swallowed. The topic carries many
 * workorder fact types this module ignores — their eventIds are still recorded so the owner's
 * manifest reconciles.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.warranty.kafka", name = "enabled", havingValue = "true")
public class WorkorderEventsListener {

    /** Producing domain, per the repo-wide processed_events convention (manifest scans key on it). */
    static final String OWNER = "workorder";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final AutoRegistrationService autoRegistrationService;

    @KafkaListener(
            topics = "${pos.warranty.kafka.workorder-events-topic:workorder.events.v1}",
            groupId = "${pos.warranty.kafka.workorder-events-consumer-group:pos-warranty-workorder-events}")
    @Transactional
    public void onWorkorderEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable workorder event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping workorder event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            if (WorkorderUpdatedV1.EVENT_TYPE.equals(eventType)) {
                WorkorderUpdatedV1 payload =
                        objectMapper.treeToValue(envelope.path("payload"), WorkorderUpdatedV1.class);
                autoRegistrationService.registerFromWorkorderSale(payload);
            } else {
                // Ignored types still fall through to the processed_events insert below: the
                // owner's manifest counts every fact in the window.
                log.debug("Ignoring workorder event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed workorder event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }
}
