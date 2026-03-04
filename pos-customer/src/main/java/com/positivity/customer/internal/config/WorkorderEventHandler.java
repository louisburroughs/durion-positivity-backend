package com.positivity.customer.internal.config;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.customer.internal.entity.CommunicationPreference;
import com.positivity.customer.internal.entity.ProcessingLog;
import com.positivity.customer.internal.enums.ProcessingStatus;
import com.positivity.customer.internal.event.ContactPreferenceUpdatedPayload;
import com.positivity.customer.internal.event.EventEnvelope;
import com.positivity.customer.internal.event.PartyNoteAddedPayload;
import com.positivity.customer.internal.event.VehicleUpdatedPayload;
import com.positivity.customer.internal.repository.CommunicationPreferenceRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.internal.repository.ProcessingLogRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka event handler consuming workorder-originated updates from the CRM topic.
 *
 * <p>Processes {@code VehicleUpdated}, {@code ContactPreferenceUpdated}, and
 * {@code PartyNoteAdded} events with idempotency and atomicity guarantees.</p>
 *
 * <p>This component is conditionally activated by {@code pos.customer.kafka.enabled=true}.
 * Set this property to {@code false} (default) to disable the listener
 * in environments where Kafka is not available.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.customer.kafka", name = "enabled", havingValue = "true")
public class WorkorderEventHandler {

    private static final String DUPLICATE_EVENT_MESSAGE = "Skipping duplicate event [eventId={}] — already processed";

    private final ProcessingLogRepository processingLogRepository;
    private final CommunicationPreferenceRepository communicationPreferenceRepository;
    private final PersonPartyRepository personPartyRepository;
    private final ObjectMapper objectMapper;

    /**
     * Primary Kafka listener entry-point. Receives raw JSON messages from the
     * workorder events topic and dispatches to the appropriate typed handler.
     *
     * @param message the raw JSON string of the inbound event envelope
     */
    @KafkaListener(
            topics = "${pos.customer.kafka.workorder-events-topic}",
            groupId = "pos-customer-workorder-events"
    )
    @Transactional
    @SuppressWarnings("java:S6809")
    public void handleWorkorderEvent(@NonNull String message) {
        try {
            final EventEnvelope envelope = objectMapper.readValue(message, EventEnvelope.class);

            final String eventType = envelope.getEventType();
            if ("VehicleUpdated".equals(eventType)) {
                handleVehicleUpdated(envelope);
                return;
            }
            if ("ContactPreferenceUpdated".equals(eventType)) {
                handleContactPreferenceUpdated(envelope);
                return;
            }
            if ("PartyNoteAdded".equals(eventType)) {
                handlePartyNoteAdded(envelope);
                return;
            }

            log.warn("Unsupported workorder event type: {}", eventType);
            processingLogRepository.save(ProcessingLog.builder()
                    .eventId(envelope.getEventId())
                    .eventType(eventType)
                    .correlationId(envelope.getCorrelationId())
                    .status(ProcessingStatus.SCHEMA_VALIDATION_FAILED)
                    .failureReason("Unsupported event type: " + eventType)
                    .processedAt(Instant.now())
                    .build());
        } catch (JacksonException ex) {
            log.error("Failed to deserialize workorder event payload", ex);
            processingLogRepository.save(ProcessingLog.builder()
                    .eventId("SCHEMA-" + UUID.randomUUID())
                    .eventType("UNKNOWN")
                    .status(ProcessingStatus.SCHEMA_VALIDATION_FAILED)
                    .failureReason(ex.getMessage())
                    .processedAt(Instant.now())
                    .build());
        } catch (Exception ex) {
            log.error("Unhandled exception while processing workorder event", ex);
        }
    }

    /**
     * Handles a {@code VehicleUpdated} event payload.
     *
     * @param envelope the deserialized event envelope
     */
    void handleVehicleUpdated(@NonNull EventEnvelope envelope) {
        if (processingLogRepository.findByEventId(envelope.getEventId()).isPresent()) {
            log.debug(DUPLICATE_EVENT_MESSAGE, envelope.getEventId());
            return;
        }

        try {
            final Map<String, Object> payloadMap = envelope.getPayload() == null ? Map.of() : envelope.getPayload();
            objectMapper.convertValue(payloadMap, VehicleUpdatedPayload.class);
            processingLogRepository.save(ProcessingLog.builder()
                    .eventId(envelope.getEventId())
                    .eventType(envelope.getEventType())
                    .correlationId(envelope.getCorrelationId())
                    .status(ProcessingStatus.SUCCESS)
                    .processedAt(Instant.now())
                    .build());
        } catch (Exception ex) {
            log.error("VehicleUpdated payload processing failed for eventId={}", envelope.getEventId(), ex);
            processingLogRepository.save(ProcessingLog.builder()
                    .eventId(envelope.getEventId())
                    .eventType(envelope.getEventType())
                    .correlationId(envelope.getCorrelationId())
                    .status(ProcessingStatus.SCHEMA_VALIDATION_FAILED)
                    .failureReason(ex.getMessage())
                    .processedAt(Instant.now())
                    .build());
        }
    }

    /**
     * Handles a {@code ContactPreferenceUpdated} event payload.
     *
     * @param envelope the deserialized event envelope
     */
    void handleContactPreferenceUpdated(@NonNull EventEnvelope envelope) {
        if (processingLogRepository.findByEventId(envelope.getEventId()).isPresent()) {
            log.debug(DUPLICATE_EVENT_MESSAGE, envelope.getEventId());
            return;
        }

        try {
            final Map<String, Object> payloadMap = envelope.getPayload() == null ? Map.of() : envelope.getPayload();
            final ContactPreferenceUpdatedPayload payload = objectMapper.convertValue(
                payloadMap,
                ContactPreferenceUpdatedPayload.class);
            final String partyIdValue = payload.getPartyId() == null
                ? String.valueOf(payloadMap.getOrDefault("partyId", ""))
                : payload.getPartyId();
            final UUID partyId = UUID.fromString(partyIdValue);

            final CommunicationPreference preference = communicationPreferenceRepository
                .findByPartyId(partyId)
                .orElseGet(() -> CommunicationPreference.builder().partyId(partyId).build());

            preference.setEmailPreference(payload.getEmailPreference());
            preference.setSmsPreference(payload.getSmsPreference());
            preference.setPhonePreference(payload.getPhonePreference());
            preference.setMarketingPreference(payload.getMarketingPreference());
            communicationPreferenceRepository.save(preference);

            processingLogRepository.save(ProcessingLog.builder()
                .eventId(envelope.getEventId())
                .eventType(envelope.getEventType())
                .correlationId(envelope.getCorrelationId())
                .status(ProcessingStatus.SUCCESS)
                .processedAt(Instant.now())
                .build());
        } catch (Exception ex) {
            log.error("ContactPreferenceUpdated processing failed for eventId={}", envelope.getEventId(), ex);
            processingLogRepository.save(ProcessingLog.builder()
                .eventId(envelope.getEventId())
                .eventType(envelope.getEventType())
                .correlationId(envelope.getCorrelationId())
                .status(ProcessingStatus.SCHEMA_VALIDATION_FAILED)
                .failureReason(ex.getMessage())
                .processedAt(Instant.now())
                .build());
        }
    }

    /**
     * Handles a {@code PartyNoteAdded} event payload.
     *
     * @param envelope the deserialized event envelope
     */
    void handlePartyNoteAdded(@NonNull EventEnvelope envelope) {
        if (processingLogRepository.findByEventId(envelope.getEventId()).isPresent()) {
            log.debug(DUPLICATE_EVENT_MESSAGE, envelope.getEventId());
            return;
        }

        try {
            final Map<String, Object> payloadMap = envelope.getPayload() == null ? Map.of() : envelope.getPayload();
            final PartyNoteAddedPayload payload = objectMapper.convertValue(payloadMap, PartyNoteAddedPayload.class);
            final String partyIdValue = payload.getPartyId() == null
                    ? String.valueOf(payloadMap.getOrDefault("partyId", ""))
                    : payload.getPartyId();
            final UUID partyId = UUID.fromString(partyIdValue);

            if (personPartyRepository.findByPersonId(partyId).isEmpty()) {
                processingLogRepository.save(ProcessingLog.builder()
                        .eventId(envelope.getEventId())
                        .eventType(envelope.getEventType())
                        .correlationId(envelope.getCorrelationId())
                        .status(ProcessingStatus.BUSINESS_RULE_VIOLATION)
                        .failureReason("Party not found: " + payload.getPartyId())
                        .processedAt(Instant.now())
                        .build());
                return;
            }

            processingLogRepository.save(ProcessingLog.builder()
                    .eventId(envelope.getEventId())
                    .eventType(envelope.getEventType())
                    .correlationId(envelope.getCorrelationId())
                    .status(ProcessingStatus.SUCCESS)
                    .processedAt(Instant.now())
                    .build());
        } catch (Exception ex) {
            log.error("PartyNoteAdded processing failed for eventId={}", envelope.getEventId(), ex);
            processingLogRepository.save(ProcessingLog.builder()
                    .eventId(envelope.getEventId())
                    .eventType(envelope.getEventType())
                    .correlationId(envelope.getCorrelationId())
                    .status(ProcessingStatus.SCHEMA_VALIDATION_FAILED)
                    .failureReason(ex.getMessage())
                    .processedAt(Instant.now())
                    .build());
        }
    }
}
