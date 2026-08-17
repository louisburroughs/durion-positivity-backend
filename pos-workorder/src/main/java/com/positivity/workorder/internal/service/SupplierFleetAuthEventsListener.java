package com.positivity.workorder.internal.service;

import com.positivity.domainevents.supplier.SupplierWorkorderAuthDeniedV1;
import com.positivity.domainevents.supplier.SupplierWorkorderAuthGrantedV1;
import com.positivity.workorder.internal.entity.ProcessedEvent;
import com.positivity.workorder.internal.enums.FleetAuthorizationStatus;
import com.positivity.workorder.internal.repository.ProcessedEventRepository;
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
 * Records what a fleet program decided about paying for a workorder (#1346, ADR-0044 R1).
 *
 * <h2>Preserved, not reinterpreted</h2>
 *
 * pos-supplier evaluates the authorization; this domain owns the record and the execution gate. The
 * supplier result distinguishes granted, refused and pending, and that distinction survives intact
 * here — a listener that collapsed "the fleet declined" into "not authorized" would lose the only
 * information an advisor can act on.
 *
 * <h2>Nothing here transitions a workorder</h2>
 *
 * An authorization outcome is an input to workexec's workflow, not a step in it. A refusal does not
 * cancel the job and does not convert it to customer-pay; the domain ruled an advisor decides that
 * explicitly. All this does is write down what the fleet said.
 *
 * <h2>Absence of an event is not a decision</h2>
 *
 * Most workorders are not fleet work and will never receive one of these. pos-supplier also
 * deliberately publishes nothing when it could not reach the vendor, so silence here means "no news"
 * rather than "refused" — which is why the start gate is closed by default rather than opened by
 * default and shut on a refusal.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "workorder.kafka", name = "enabled", havingValue = "true")
public class SupplierFleetAuthEventsListener {

    private static final String OWNER = "workorder-fleetauth";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final FleetAuthorizationService fleetAuthorizationService;

    @KafkaListener(
            topics = "${workorder.kafka.supplier-events-topic:supplier.events.v1}",
            groupId = "${workorder.kafka.supplier-events-consumer-group:pos-workorder-supplier-events}")
    @Transactional
    public void onSupplierEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable supplier event", e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping supplier event without eventId");
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            if (SupplierWorkorderAuthGrantedV1.EVENT_TYPE.equals(eventType)) {
                applyGranted(envelope);
            } else if (SupplierWorkorderAuthDeniedV1.EVENT_TYPE.equals(eventType)) {
                applyDenied(envelope);
            } else {
                log.debug("Ignoring supplier event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            // Rethrown so the container retries. Marking this processed would leave a workorder
            // gated on an authorization the fleet has already granted, with nothing to correct it.
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed supplier fleet-authorization event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyGranted(@NonNull JsonNode envelope) {
        SupplierWorkorderAuthGrantedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), SupplierWorkorderAuthGrantedV1.class);
        fleetAuthorizationService.recordDecision(
                payload.workorderId(),
                payload.vendorProfileId(),
                FleetAuthorizationStatus.GRANTED,
                null,
                null,
                payload.contractReference());
    }

    private void applyDenied(@NonNull JsonNode envelope) {
        SupplierWorkorderAuthDeniedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), SupplierWorkorderAuthDeniedV1.class);
        // The vendor's own words, kept unchanged: a refusal reason is what an advisor quotes back to
        // a fleet manager, so translating it would cost the conversation its evidence.
        fleetAuthorizationService.recordDecision(
                payload.workorderId(),
                payload.vendorProfileId(),
                FleetAuthorizationStatus.REFUSED,
                payload.reasonCode(),
                payload.reasonText(),
                null);
    }
}
