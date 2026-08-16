package com.positivity.supplier.internal.workorderauth.service;

import com.positivity.domainevents.workorder.WorkorderServiceCompletedV1;
import com.positivity.supplier.internal.entity.ProcessedEvent;
import com.positivity.supplier.internal.repository.ProcessedEventRepository;
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
 * Queues vendor sign-off when a fleet workorder completes (CAP-323 #1229, ADR-0044 R1).
 *
 * <h2>Consuming, not asking</h2>
 *
 * pos-supplier learns that work finished from a workexec fact, never by reading pos-workorder. This
 * is the direction the domain wall permits, and it is also the only direction that works: a
 * completion is a moment in time, and a poller asking "has it finished yet" would either miss it or
 * hammer a domain that has no reason to answer.
 *
 * <h2>Most completions are not fleet work</h2>
 *
 * The great majority of workorders have no authorization at all, so the common path here is to
 * record the event as processed and do nothing. That is why the lookup is by workorder id against
 * this module's own table rather than anything more elaborate — an ordinary retail job must cost
 * one indexed read.
 *
 * <h2>The vendor is not called here</h2>
 *
 * Completion only marks the row as needing approval; the approver sends it later on its own
 * schedule. A fleet API being unreachable must never fail the handling of a workorder completion,
 * because the completion is a fact that already happened and refusing to record it does not make it
 * un-happen.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.supplier.kafka", name = "enabled", havingValue = "true")
public class WorkorderCompletionEventsListener {

    private static final String OWNER = "supplier-workorderauth";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final WorkorderCompletionApprover approver;

    @KafkaListener(
            topics = "${pos.supplier.kafka.workorder-events-topic:workorder.events.v1}",
            groupId = "${pos.supplier.kafka.workorder-events-consumer-group:pos-supplier-workorder-events}")
    @Transactional
    public void onWorkorderEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable workorder event", e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping workorder event without eventId");
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            if (WorkorderServiceCompletedV1.EVENT_TYPE.equals(eventType)) {
                applyCompletion(envelope);
            } else {
                log.debug("Ignoring workorder event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            // Rethrown so the container retries. Marking this processed would leave an authorized
            // job never queued for sign-off, which is work performed that the fleet is never asked
            // to pay for -- and nothing later would report it missing.
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed workorder completion event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyCompletion(@NonNull JsonNode envelope) {
        WorkorderServiceCompletedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), WorkorderServiceCompletedV1.class);
        approver.queueApproval(payload.workorderId())
                .ifPresent(row -> log.info(
                        "Queued vendor approval for completed workorder {} at {}",
                        row.getWorkorderId(),
                        row.getSupplierRef()));
    }
}
