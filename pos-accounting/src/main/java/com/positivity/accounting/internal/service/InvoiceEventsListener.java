package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.ExtInvoiceTax;
import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceTaxRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.invoice.InvoiceUpdatedV1;
import com.positivity.domainevents.invoice.TaxBreakdownLine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
 * Consumes {@code invoice.events.v1} into accounting's {@code ext_invoice} replica (ADR-0044,
 * #842).
 *
 * <p>Same contract as {@link CustomerEventsListener}: idempotent via {@code processed_events} in
 * the upsert transaction, stale envelopes (aggregateVersion strictly below the replica's) skipped,
 * transient DB errors rethrown for container retry/DLQ, malformed payloads logged and skipped.
 * Event types other than {@link InvoiceUpdatedV1#EVENT_TYPE} are not replicated but still recorded
 * in {@code processed_events} (#1537 F1): pos-invoice publishes more than one event type onto this
 * topic, and {@code InvoiceManifestListener}'s window count must match {@code ManifestPublisher}'s,
 * which counts every fact regardless of type.
 *
 * <p>The stale guard is {@link ReplicaVersionGuard} (#1486): pos-invoice's {@code
 * InvoiceEventPublisher} flushes the invoice's JPA {@code @Version} before emit, so the version
 * strictly advances — an equal version applies rather than skips: it is an idempotent no-op for
 * live traffic, and it is what would let a regenerate-from-state replay (the catalog/vehicle
 * {@code facts/replay} pattern, should pos-invoice grow one) repair a replica that holds the
 * version number but wrong or missing rows.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.accounting.kafka", name = "enabled", havingValue = "true")
public class InvoiceEventsListener {

    /**
     * Producing domain, per the repo-wide {@code processed_events} convention — stamped on every
     * row so {@code InvoiceManifestListener} (#1537 D2) can scope its window scan to exactly the
     * events this listener recorded, since {@code processed_events} here is shared by every one
     * of this module's Kafka listeners.
     */
    static final String OWNER = "invoice";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtInvoiceRepository extInvoiceRepository;
    private final ExtInvoiceTaxRepository extInvoiceTaxRepository;
    private final Counter payloadRejectedCounter;

    public InvoiceEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtInvoiceRepository extInvoiceRepository,
            ExtInvoiceTaxRepository extInvoiceTaxRepository,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extInvoiceRepository = extInvoiceRepository;
        this.extInvoiceTaxRepository = extInvoiceTaxRepository;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", OWNER)
                        .tag("entity", "invoice-events")
                        .register(registry);
    }

    @KafkaListener(
            topics = "${pos.accounting.kafka.invoice-events-topic:invoice.events.v1}",
            groupId = "pos-accounting-invoice-events")
    @Transactional
    public void onInvoiceEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable invoice event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping invoice event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate invoice event eventId={}", eventId);
            return;
        }

        try {
            if (InvoiceUpdatedV1.EVENT_TYPE.equals(eventType)) {
                applyInvoiceUpdate(envelope);
            } else {
                // Ignored types still fall through to the processed_events insert below: the
                // owner's manifest counts every fact in the window (#1537 F1) — pos-invoice's
                // InvoiceEventPublisher also publishes invoice.billing-rules.updated onto this
                // same topic, and ManifestPublisher's window count includes it regardless of type.
                log.debug("Ignoring invoice event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            // Retry with backoff / DLQ via the container error handler (ADR-0044 §4).
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed invoice event payload eventId={}: {}", eventId, e.getMessage(), e);
        } catch (Exception e) {
            log.warn("Skipping malformed invoice event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyInvoiceUpdate(JsonNode envelope) {
        InvoiceUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), InvoiceUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        UUID invoiceId = payload.invoiceId();

        ExtInvoice existing = extInvoiceRepository.findById(invoiceId).orElse(null);
        // Versions are strictly increasing per invoice (committed JPA @Version, flushed before
        // emit), so version 0 (the create) participates in the comparison too — a late or
        // replayed version-0 event must never overwrite a newer replica row (PR #850 review).
        // Strictly-newer-only skip: equal versions APPLY (#1486, ReplicaVersionGuard) — equal
        // means identical content, and replay resends the held version deliberately to repair
        // wrong or missing rows.
        if (existing != null && ReplicaVersionGuard.isStale(existing.getAggregateVersion(), aggregateVersion)) {
            log.debug(
                    "Skipping stale invoice event invoiceId={} eventVersion={} replicaVersion={}",
                    invoiceId,
                    aggregateVersion,
                    existing.getAggregateVersion());
            return;
        }

        extInvoiceRepository.save(ExtInvoice.builder()
                .invoiceId(invoiceId)
                .invoiceNumber(payload.invoiceNumber())
                .workorderId(payload.workorderId())
                .estimateId(payload.estimateId())
                .locationId(payload.locationId())
                .partyId(payload.partyId())
                .status(payload.status())
                .subtotal(payload.subtotal())
                .tax(payload.tax())
                .total(payload.total())
                .adjustmentsAmount(payload.adjustmentsAmount())
                .invoiceCreatedAt(payload.createdAt())
                .finalizedAt(payload.finalizedAt())
                .dueDate(payload.dueDate())
                .depositSourceType(payload.depositSourceType())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        replaceTaxBreakdown(invoiceId, aggregateVersion, payload.taxBreakdown());
        log.info(
                "Updated ext_invoice replica invoiceId={} status={} version={}",
                invoiceId,
                payload.status(),
                aggregateVersion);
    }

    /**
     * Replicate the per-line jurisdiction breakdown into {@code ext_invoice_tax} (story T5c),
     * replacing the invoice's rows to match to the cent. Runs only on the non-stale path (the
     * same {@code aggregateVersion} guard that protects {@link ExtInvoice} above). A {@code null}
     * breakdown (older events / non-breakdown producers) leaves existing tax rows untouched.
     */
    private void replaceTaxBreakdown(
            @NonNull UUID invoiceId, long aggregateVersion, @Nullable List<TaxBreakdownLine> breakdown) {
        if (breakdown == null) {
            return;
        }
        extInvoiceTaxRepository.deleteByInvoiceId(invoiceId);
        if (breakdown.isEmpty()) {
            return;
        }
        Instant now = Instant.now(clock);
        List<ExtInvoiceTax> rows = breakdown.stream()
                .map(line -> ExtInvoiceTax.builder()
                        .invoiceId(invoiceId)
                        .lineItemId(line.lineItemId())
                        .jurisdictionType(line.jurisdictionType())
                        .jurisdictionCode(line.jurisdictionCode())
                        .rate(line.rate())
                        .taxableBase(line.taxableBase())
                        .taxAmount(line.taxAmount())
                        .exempt(line.exempt())
                        .exemptionReasonCode(line.exemptionReasonCode())
                        .aggregateVersion(aggregateVersion)
                        .updatedAt(now)
                        .build())
                .toList();
        extInvoiceTaxRepository.saveAll(rows);
    }
}
