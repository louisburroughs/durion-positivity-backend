package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.InvoiceRegenerationRequest;
import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.InvoiceRegenerationRequestRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.workorder.WorkorderServiceCompletedV1;
import com.positivity.domainevents.workorder.WorkorderUpdatedV1;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
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
 * keyed by workorderId, carrying the workorder's pre-existing invoiceId (if any) as {@link
 * InvoiceRegenerationRequest#getPriorInvoiceId()}.
 *
 * <p><b>#1537 F4 — resolution semantics.</b> Neither {@link WorkorderUpdatedV1} nor {@link
 * WorkorderServiceCompletedV1} carries the command's {@code commandId} (pos-workorder is out of
 * scope to change here), so a fact cannot be correlated back to the exact command that requested
 * it. Both event types are, however, one-snapshot-per-transaction facts: {@code invoiceId} is
 * just the workorder's current back-reference, emitted on <em>any</em> touch to the workorder —
 * including edits that have nothing to do with invoicing. Resolving on presence of a non-null
 * {@code invoiceId} alone therefore let an unrelated edit falsely resolve a regeneration request
 * with the invoice that already existed (and was the very reason regeneration was requested).
 * A fact resolves a pending request only when both hold:
 *
 * <ol>
 *   <li><b>It post-dates the request</b> — the fact's own timestamp ({@link
 *       WorkorderUpdatedV1#updatedAt()} / {@link WorkorderServiceCompletedV1#completedAt()}) is
 *       not before {@link InvoiceRegenerationRequest#getRequestedAt()}. A {@code null}
 *       {@code updatedAt} (pre-#924 producers) cannot be verified and is treated as not
 *       resolving — safer to under-resolve (and let the TTL reaper below catch it) than to
 *       falsely resolve on an unverifiable timestamp.
 *   <li><b>Its invoiceId differs from {@link InvoiceRegenerationRequest#getPriorInvoiceId()}</b>
 *       — the id the workorder already had when the command was published. An echo of that same
 *       id is exactly the false-positive scenario above, not evidence regeneration did anything.
 * </ol>
 *
 * <p><b>Reaping stuck requests.</b> pos-workorder's {@code KafkaCommandListener} swallows business
 * failures for the regenerate command (workorder missing / not eligible) and logs-and-drops, so a
 * genuinely failed regeneration emits no fact at all and would otherwise leave its row {@code
 * PENDING} forever. {@link #reapExpiredRequests()} runs on a fixed schedule and marks any
 * {@code PENDING} row older than {@link #pendingTtl} {@link
 * InvoiceRegenerationRequest#STATUS_FAILED}, making that terminal-but-unresolved state visible and
 * queryable instead of an invisible permanent PENDING.
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

    /**
     * How long a {@code PENDING} regeneration request may go unresolved before {@link
     * #reapExpiredRequests()} marks it {@link InvoiceRegenerationRequest#STATUS_FAILED} (#1537 F4).
     */
    @Value("${pos.accounting.invoice-regeneration.pending-ttl:PT30M}")
    private Duration pendingTtl = Duration.ofMinutes(30);

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
        Instant factTimestamp;
        if (WorkorderUpdatedV1.EVENT_TYPE.equals(eventType)) {
            WorkorderUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), WorkorderUpdatedV1.class);
            workorderId = payload.workorderId();
            invoiceId = payload.invoiceId();
            factTimestamp = payload.updatedAt();
        } else {
            WorkorderServiceCompletedV1 payload =
                    objectMapper.treeToValue(envelope.path("payload"), WorkorderServiceCompletedV1.class);
            workorderId = payload.workorderId();
            invoiceId = payload.invoiceId();
            factTimestamp = payload.completedAt();
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
        List<InvoiceRegenerationRequest> resolved = pending.stream()
                .filter(request -> resolves(request, invoiceId, factTimestamp, workorderId))
                .toList();
        if (resolved.isEmpty()) {
            log.debug(
                    "Workorder fact for workorderId={} invoiceId={} resolves no pending request"
                            + " (unverifiable/stale timestamp or echoes the prior invoiceId)",
                    workorderId,
                    invoiceId);
            return;
        }
        for (InvoiceRegenerationRequest request : resolved) {
            request.setStatus(InvoiceRegenerationRequest.STATUS_COMPLETED);
            request.setResultInvoiceId(invoiceId);
            request.setResolvedAt(now);
        }
        invoiceRegenerationRequestRepository.saveAll(resolved);
        log.info(
                "Resolved {} invoice regeneration request(s) workorderId={} invoiceId={}",
                resolved.size(),
                workorderId,
                invoiceId);
    }

    /**
     * #1537 F4: true only when the fact both post-dates the request and carries an invoiceId
     * other than the one the requester already had — see the class javadoc.
     */
    private boolean resolves(
            @NonNull InvoiceRegenerationRequest request,
            @NonNull UUID factInvoiceId,
            @Nullable Instant factTimestamp,
            @NonNull UUID workorderId) {
        if (factTimestamp == null) {
            log.debug(
                    "Workorder fact for workorderId={} carries no timestamp; cannot verify it post-dates"
                            + " requestedAt={} — leaving request pending",
                    workorderId,
                    request.getRequestedAt());
            return false;
        }
        if (factTimestamp.isBefore(request.getRequestedAt())) {
            log.debug(
                    "Workorder fact timestamp={} for workorderId={} predates requestedAt={}; ignoring as stale",
                    factTimestamp,
                    workorderId,
                    request.getRequestedAt());
            return false;
        }
        if (factInvoiceId.equals(request.getPriorInvoiceId())) {
            log.debug(
                    "Workorder fact for workorderId={} echoes the prior invoiceId={}; not evidence of"
                            + " regeneration",
                    workorderId,
                    factInvoiceId);
            return false;
        }
        return true;
    }

    /**
     * Reaps {@code PENDING} regeneration requests older than {@link #pendingTtl} to {@link
     * InvoiceRegenerationRequest#STATUS_FAILED} (#1537 F4). pos-workorder's {@code
     * KafkaCommandListener} swallows business failures for the regenerate command, so these rows
     * would otherwise stay {@code PENDING} forever with no fact ever arriving to resolve them.
     */
    @Scheduled(fixedRateString = "${pos.accounting.invoice-regeneration.reap-interval-ms:300000}")
    public void reapExpiredRequests() {
        Instant cutoff = Instant.now(clock).minus(pendingTtl);
        List<InvoiceRegenerationRequest> expired =
                invoiceRegenerationRequestRepository.findByStatusAndRequestedAtBefore(
                        InvoiceRegenerationRequest.STATUS_PENDING, cutoff);
        if (expired.isEmpty()) {
            return;
        }
        Instant now = Instant.now(clock);
        for (InvoiceRegenerationRequest request : expired) {
            request.setStatus(InvoiceRegenerationRequest.STATUS_FAILED);
            request.setResolvedAt(now);
        }
        invoiceRegenerationRequestRepository.saveAll(expired);
        log.warn("Reaped {} invoice regeneration request(s) stuck PENDING past ttl={}", expired.size(), pendingTtl);
    }
}
