package com.positivity.customer.internal.event.handler;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.positivity.customer.internal.entity.CommunicationPreference;
import com.positivity.customer.internal.entity.PartyNote;
import com.positivity.customer.internal.entity.ProcessingLog;
import com.positivity.customer.internal.entity.VehicleProjection;
import com.positivity.customer.internal.enums.ProcessingStatus;
import com.positivity.customer.internal.event.ContactPreferenceUpdatedPayload;
import com.positivity.customer.internal.event.EventEnvelope;
import com.positivity.customer.internal.event.PartyNoteAddedPayload;
import com.positivity.customer.internal.event.VehicleUpdatedPayload;
import com.positivity.customer.internal.repository.CommunicationPreferenceRepository;
import com.positivity.customer.internal.repository.PartyNoteRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.internal.repository.ProcessingLogRepository;
import com.positivity.customer.internal.repository.VehicleProjectionRepository;
import com.positivity.shared.id.UUIDv7Generator;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka event handler consuming workorder-originated updates from the CRM
 * topic.
 *
 * <p>
 * Processes {@code VehicleUpdated}, {@code ContactPreferenceUpdated}, and
 * {@code PartyNoteAdded} events with idempotency and atomicity guarantees.
 * </p>
 *
 * <p>
 * This component is conditionally activated by
 * {@code pos.customer.kafka.enabled=true}.
 * Set this property to {@code false} (default) to disable the listener
 * in environments where Kafka is not available.
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.customer.kafka", name = "enabled", havingValue = "true")
public class WorkorderEventHandler {
    private final Clock clock;

    private static final String UNKNOWN_EVENT_TYPE = "UNKNOWN";
    private static final String DUPLICATE_EVENT_MESSAGE = "Skipping duplicate event eventId={} eventType={} status=already_processed";
    private static final String DUPLICATE_SAVE_COLLISION_MESSAGE = "Concurrent duplicate save collision suppressed for eventId={}";
    private static final String DUPLICATE_FAILURE_REASON_PREFIX = "Duplicate of eventId: ";
    private static final int MAX_FAILURE_REASON_LENGTH = 490;

    private final ProcessingLogRepository processingLogRepository;
    private final CommunicationPreferenceRepository communicationPreferenceRepository;
    private final PersonPartyRepository personPartyRepository;
    private final PartyNoteRepository partyNoteRepository;
    private final VehicleProjectionRepository vehicleProjectionRepository;
    private final ObjectMapper objectMapper;

    /**
     * Primary Kafka listener entry-point. Receives raw JSON messages from the
     * workorder events topic and dispatches to the appropriate typed handler.
     *
     * @param message the raw JSON string of the inbound event envelope
     */
    @KafkaListener(topics = "${pos.customer.kafka.workorder-events-topic}", groupId = "pos-customer-workorder-events")
    @Transactional
    @SuppressWarnings("java:S6809")
    public void handleWorkorderEvent(@NonNull String message) {
        try {
            final EventEnvelope envelope = objectMapper.readValue(message, EventEnvelope.class);

            if (dispatchByEventType(envelope)) {
                return;
            }

            final String safeEventType = normalizeEventType(envelope.getEventType());
            log.warn("Unsupported workorder event type eventId={} eventType={}", envelope.getEventId(), safeEventType);
            processingLogRepository.save(ProcessingLog.builder()
                    .eventId((envelope.getEventId() == null || envelope.getEventId().isBlank())
                            ? UUIDv7Generator.generate().toString()
                            : envelope.getEventId())
                    .eventType(safeEventType)
                    .correlationId(envelope.getCorrelationId())
                    .status(ProcessingStatus.SCHEMA_VALIDATION_FAILED)
                    .failureReason(truncateFailureReason("Unsupported event type: " + safeEventType))
                    .processedAt(Instant.now(clock))
                    .build());
        } catch (JacksonException ex) {
            log.error("Failed to deserialize workorder event payload", ex);
            processingLogRepository.save(ProcessingLog.builder()
                    .eventId(UUIDv7Generator.generate().toString())
                    .eventType(UNKNOWN_EVENT_TYPE)
                    .status(ProcessingStatus.SCHEMA_VALIDATION_FAILED)
                    .failureReason(truncateFailureReason("SCHEMA validation failed: " + ex.getMessage()))
                    .processedAt(Instant.now(clock))
                    .build());
        } catch (Exception ex) {
            log.error("Unhandled exception while processing workorder event", ex);
            processingLogRepository.save(ProcessingLog.builder()
                    .eventId(UUIDv7Generator.generate().toString())
                    .eventType(UNKNOWN_EVENT_TYPE)
                    .status(ProcessingStatus.SCHEMA_VALIDATION_FAILED)
                    .failureReason("Unhandled exception: " + (ex.getMessage() != null
                            ? ex.getMessage().substring(0, Math.min(ex.getMessage().length(), 490))
                            : "null"))
                    .processedAt(Instant.now(clock))
                    .build());
        }
    }

    /**
     * Handles a {@code VehicleUpdated} event payload.
     *
     * @param envelope the deserialized event envelope
     */
    void handleVehicleUpdated(@NonNull EventEnvelope envelope) {
        if (processingLogRepository.findByEventId(envelope.getEventId()).isPresent()) {
            log.debug(DUPLICATE_EVENT_MESSAGE, envelope.getEventId(), envelope.getEventType());
            try {
                processingLogRepository.save(ProcessingLog.builder()
                        .eventId(UUIDv7Generator.generate().toString())
                        .eventType(envelope.getEventType())
                        .correlationId(envelope.getCorrelationId())
                        .status(ProcessingStatus.SKIPPED_DUPLICATE)
                        .failureReason(truncateFailureReason(DUPLICATE_FAILURE_REASON_PREFIX + envelope.getEventId()))
                        .processedAt(Instant.now(clock))
                        .build());
            } catch (org.springframework.dao.DataIntegrityViolationException dive) {
                log.debug(DUPLICATE_SAVE_COLLISION_MESSAGE, envelope.getEventId());
            }
            return;
        }

        try {
            final Map<String, Object> payloadMap = envelope.getPayload() == null ? Map.of() : envelope.getPayload();
            final VehicleUpdatedPayload payload = objectMapper.convertValue(payloadMap, VehicleUpdatedPayload.class);
            upsertVehicleProjection(payload);

            processingLogRepository.save(ProcessingLog.builder()
                    .eventId(envelope.getEventId())
                    .eventType(envelope.getEventType())
                    .correlationId(envelope.getCorrelationId())
                    .status(ProcessingStatus.SUCCESS)
                    .processedAt(Instant.now(clock))
                    .build());
        } catch (Exception ex) {
            log.error("VehicleUpdated payload processing failed eventId={} eventType={}",
                    envelope.getEventId(),
                    envelope.getEventType(),
                    ex);
            processingLogRepository.save(ProcessingLog.builder()
                    .eventId(envelope.getEventId())
                    .eventType(envelope.getEventType())
                    .correlationId(envelope.getCorrelationId())
                    .status(ProcessingStatus.SCHEMA_VALIDATION_FAILED)
                    .failureReason(truncateFailureReason(ex.getMessage()))
                    .processedAt(Instant.now(clock))
                    .build());
        }
    }

    private void upsertVehicleProjection(@NonNull VehicleUpdatedPayload payload) {
        if (!StringUtils.hasText(payload.getVehicleId())) {
            log.warn("VehicleUpdated payload missing vehicleId; skipping vehicle projection update");
            return;
        }

        final UUID vehicleId = UUID.fromString(payload.getVehicleId());
        final VehicleProjection projection = vehicleProjectionRepository.findById(vehicleId)
                .orElseGet(() -> VehicleProjection.builder().vehicleId(vehicleId).build());

        if (payload.getVin() != null) {
            projection.setVin(normalizeOptionalText(payload.getVin()));
        }
        if (payload.getMake() != null) {
            projection.setMake(normalizeOptionalText(payload.getMake()));
        }
        if (payload.getModel() != null) {
            projection.setModel(normalizeOptionalText(payload.getModel()));
        }
        if (payload.getColor() != null) {
            projection.setColor(normalizeOptionalText(payload.getColor()));
        }
        if (payload.getYear() != null) {
            projection.setYear(payload.getYear());
        }
        projection.setLastEventAt(Instant.now(clock));
        vehicleProjectionRepository.save(projection);
    }

    private String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Handles a {@code ContactPreferenceUpdated} event payload.
     *
     * @param envelope the deserialized event envelope
     */
    void handleContactPreferenceUpdated(@NonNull EventEnvelope envelope) {
        if (processingLogRepository.findByEventId(envelope.getEventId()).isPresent()) {
            log.debug(DUPLICATE_EVENT_MESSAGE, envelope.getEventId(), envelope.getEventType());
            try {
                processingLogRepository.save(ProcessingLog.builder()
                        .eventId(UUIDv7Generator.generate().toString())
                        .eventType(envelope.getEventType())
                        .correlationId(envelope.getCorrelationId())
                        .status(ProcessingStatus.SKIPPED_DUPLICATE)
                        .failureReason(truncateFailureReason(DUPLICATE_FAILURE_REASON_PREFIX + envelope.getEventId()))
                        .processedAt(Instant.now(clock))
                        .build());
            } catch (org.springframework.dao.DataIntegrityViolationException dive) {
                log.debug(DUPLICATE_SAVE_COLLISION_MESSAGE, envelope.getEventId());
            }
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
                    .processedAt(Instant.now(clock))
                    .build());
        } catch (Exception ex) {
            log.error("ContactPreferenceUpdated processing failed eventId={} eventType={}",
                    envelope.getEventId(),
                    envelope.getEventType(),
                    ex);
            processingLogRepository.save(ProcessingLog.builder()
                    .eventId(envelope.getEventId())
                    .eventType(envelope.getEventType())
                    .correlationId(envelope.getCorrelationId())
                    .status(ProcessingStatus.SCHEMA_VALIDATION_FAILED)
                    .failureReason(truncateFailureReason(ex.getMessage()))
                    .processedAt(Instant.now(clock))
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
            log.debug(DUPLICATE_EVENT_MESSAGE, envelope.getEventId(), envelope.getEventType());
            try {
                processingLogRepository.save(ProcessingLog.builder()
                        .eventId(UUIDv7Generator.generate().toString())
                        .eventType(envelope.getEventType())
                        .correlationId(envelope.getCorrelationId())
                        .status(ProcessingStatus.SKIPPED_DUPLICATE)
                        .failureReason(truncateFailureReason(DUPLICATE_FAILURE_REASON_PREFIX + envelope.getEventId()))
                        .processedAt(Instant.now(clock))
                        .build());
            } catch (org.springframework.dao.DataIntegrityViolationException dive) {
                log.debug(DUPLICATE_SAVE_COLLISION_MESSAGE, envelope.getEventId());
            }
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
                        .failureReason(truncateFailureReason("Party not found: " + payload.getPartyId()))
                        .processedAt(Instant.now(clock))
                        .build());
                return;
            }

            partyNoteRepository.save(PartyNote.builder()
                    .partyId(partyId)
                    .noteText(normalizeOptionalText(payload.getNoteText()))
                    .noteType(normalizeOptionalText(payload.getNoteType()))
                    .sourceWorkorderId(normalizeOptionalText(payload.getSourceWorkorderId()))
                    .sourceEventId(envelope.getEventId())
                    .createdAt(Instant.now(clock))
                    .build());

            log.info("PartyNoteAdded accepted eventId={} eventType={} partyId={} noteType={}",
                    envelope.getEventId(),
                    envelope.getEventType(),
                    payload.getPartyId(),
                    payload.getNoteType());

            processingLogRepository.save(ProcessingLog.builder()
                    .eventId(envelope.getEventId())
                    .eventType(envelope.getEventType())
                    .correlationId(envelope.getCorrelationId())
                    .status(ProcessingStatus.SUCCESS)
                    .processedAt(Instant.now(clock))
                    .build());
        } catch (Exception ex) {
            log.error("PartyNoteAdded processing failed eventId={} eventType={}",
                    envelope.getEventId(),
                    envelope.getEventType(),
                    ex);
            processingLogRepository.save(ProcessingLog.builder()
                    .eventId(envelope.getEventId())
                    .eventType(envelope.getEventType())
                    .correlationId(envelope.getCorrelationId())
                    .status(ProcessingStatus.SCHEMA_VALIDATION_FAILED)
                    .failureReason(truncateFailureReason(ex.getMessage()))
                    .processedAt(Instant.now(clock))
                    .build());
        }
    }

    private boolean dispatchByEventType(EventEnvelope envelope) {
        final String eventType = envelope.getEventType();
        if ("VehicleUpdated".equals(eventType)) {
            handleVehicleUpdated(envelope);
            return true;
        }
        if ("ContactPreferenceUpdated".equals(eventType)) {
            handleContactPreferenceUpdated(envelope);
            return true;
        }
        if ("PartyNoteAdded".equals(eventType)) {
            handlePartyNoteAdded(envelope);
            return true;
        }
        return false;
    }

    private String normalizeEventType(String eventType) {
        return eventType == null || eventType.isBlank() ? UNKNOWN_EVENT_TYPE : eventType;
    }

    private String truncateFailureReason(String failureReason) {
        if (failureReason == null) {
            return null;
        }
        if (failureReason.length() <= MAX_FAILURE_REASON_LENGTH) {
            return failureReason;
        }
        return failureReason.substring(0, MAX_FAILURE_REASON_LENGTH);
    }
}
