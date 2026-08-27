package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.InvoiceRegenerationRequest;
import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.InvoiceRegenerationRequestRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.workorder.WorkorderServiceCompletedV1;
import com.positivity.domainevents.workorder.WorkorderUpdatedV1;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
 * Consumes {@code workorder.events.v1} and resolves accounting's own outstanding invoice
 * regeneration commands (ADR-0044, issue #1537 D1).
 *
 * <p>{@code InvoiceRegenerationServiceImpl} publishes a regeneration command to {@code
 * workorder.commands.v1} and persists a {@link InvoiceRegenerationRequest#STATUS_PENDING} row
 * keyed by workorderId. On either {@link WorkorderUpdatedV1} or {@link
 * WorkorderServiceCompletedV1} carrying a non-null {@code invoiceId}, every outstanding pending
 * request for that workorder is resolved to {@link InvoiceRegenerationRequest#STATUS_COMPLETED}
 * with the resulting invoice id.
 *
 * <p>Same contract as {@link InvoiceEventsListener}: idempotent via {@code processed_events} in
 * the resolving transaction, transient DB errors rethrown for container retry/DLQ, malformed
 * payloads logged and skipped. A fact for a workorder with no pending request is a harmless no-op
 * — still recorded as processed so redelivery does not reprocess it.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.accounting.kafka", name = "enabled", havingValue = "true")
public class WorkorderEventsListener {

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final InvoiceRegenerationRequestRepository invoiceRegenerationRequestRepository;

    public WorkorderEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            InvoiceRegenerationRequestRepository invoiceRegenerationRequestRepository) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.invoiceRegenerationRequestRepository = invoiceRegenerationRequestRepository;
    }

    @KafkaListener(
            topics = "${pos.accounting.kafka.workorder-events-topic:workorder.events.v1}",
            groupId = "pos-accounting-workorder-events")
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
        if (!WorkorderUpdatedV1.EVENT_TYPE.equals(eventType)
                && !WorkorderServiceCompletedV1.EVENT_TYPE.equals(eventType)) {
            log.debug("Ignoring workorder event type={}", eventType);
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping workorder event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate workorder event eventId={}", eventId);
            return;
        }

        try {
            resolveRegenerationRequests(eventType, envelope);
        } catch (TransientDataAccessException e) {
            // Retry with backoff / DLQ via the container error handler (ADR-0044 §4).
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed workorder event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void resolveRegenerationRequests(@NonNull String eventType, @NonNull JsonNode envelope) {
        UUID workorderId;
        UUID invoiceId;
        if (WorkorderUpdatedV1.EVENT_TYPE.equals(eventType)) {
            WorkorderUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), WorkorderUpdatedV1.class);
            workorderId = payload.workorderId();
            invoiceId = payload.invoiceId();
        } else {
            WorkorderServiceCompletedV1 payload =
                    objectMapper.treeToValue(envelope.path("payload"), WorkorderServiceCompletedV1.class);
            workorderId = payload.workorderId();
            invoiceId = payload.invoiceId();
        }
        if (invoiceId == null) {
            log.debug("Workorder fact carries no invoiceId; nothing to resolve workorderId={}", workorderId);
            return;
        }

        List<InvoiceRegenerationRequest> pending = invoiceRegenerationRequestRepository.findByWorkorderIdAndStatus(
                workorderId, InvoiceRegenerationRequest.STATUS_PENDING);
        if (pending.isEmpty()) {
            log.debug("No pending invoice regeneration requests for workorderId={}", workorderId);
            return;
        }

        Instant now = Instant.now(clock);
        for (InvoiceRegenerationRequest request : pending) {
            request.setStatus(InvoiceRegenerationRequest.STATUS_COMPLETED);
            request.setResultInvoiceId(invoiceId);
            request.setResolvedAt(now);
        }
        invoiceRegenerationRequestRepository.saveAll(pending);
        log.info(
                "Resolved {} invoice regeneration request(s) workorderId={} invoiceId={}",
                pending.size(),
                workorderId,
                invoiceId);
    }
}
