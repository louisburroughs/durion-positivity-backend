package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.AccountingEventTypeRegistry;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.ExtInvoiceTax;
import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceTaxRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.invoice.InvoiceUpdatedV1;
import com.positivity.domainevents.invoice.TaxBreakdownLine;
import com.positivity.kafka.common.KafkaRails;
import com.positivity.tenancy.kafka.RetryableConsumerFailures;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.Serial;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code invoice.events.v1} into accounting's {@code ext_invoice} replica (ADR-0044,
 * #842) and, for applied (non-stale) {@code invoice.invoice.updated} facts, into invoice revenue
 * recognition on the GL (#1843, {@link InvoiceRevenuePostingService}): {@code FINALIZED}/{@code
 * POSTED} post {@code Dr AR / Cr Service Revenue / Cr Sales Tax Payable}, {@code DRAFT}/{@code
 * CANCELLED} reverse an open recognition. Posting failures propagate unwrapped for container
 * retry / DLQ and are never marked processed.
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
 *
 * <p><b>Transaction shape (#2146).</b> The listener method is not {@code @Transactional}: the
 * replica upsert, the GL posting and the processed mark commit together in a {@code REQUIRES_NEW}
 * transaction of their own. A permanent replica failure thrown through a repository therefore
 * rolls back only that work and is recorded in a separate transaction, instead of poisoning a
 * shared transaction whose commit then throws and sends the record round the container's retry
 * ladder; transient and integrity failures, and any posting failure, still propagate unrecorded.
 *
 * <p><b>Ingestion record (#2433).</b> Every consumed {@code invoice.invoice.updated} fact writes one
 * {@code accounting_event} row ({@link #RECORDED_EVENT_TYPES}, source system {@value
 * #SOURCE_SYSTEM}, {@code domainKeyId} the invoice id) through {@link KafkaFactIngestionRecorder},
 * in the handler transaction: {@code PROCESSED / NEW} linked to the posted revenue (or reversal)
 * entry; {@code PROCESSED / DUPLICATE_IGNORED} linked to the earlier entry when the invoice's
 * revenue cycle was already posted (the {@code POSTED} fact that follows every {@code FINALIZED}
 * one lands here); {@code PROCESSED / NEW} with no entry for a zero total or a revert with nothing
 * open; {@code SKIPPED / NOT_POSTABLE} for a stale fact, a deposit-take invoice, a fact without
 * {@code finalizedAt}, or a status that neither recognizes nor reverses. A recording failure is a
 * posting failure: it propagates unmarked for retry / DLQ. Other event types on the topic write no
 * row.
 */
@Slf4j
@Component
@KafkaRails
public class InvoiceEventsListener {

    /**
     * Producing domain, per the repo-wide {@code processed_events} convention — stamped on every
     * row so {@code InvoiceManifestListener} (#1537 D2) can scope its window scan to exactly the
     * events this listener recorded, since {@code processed_events} here is shared by every one
     * of this module's Kafka listeners.
     */
    static final String OWNER = "invoice";

    /** Producing module, stamped as {@code sourceSystem} on this listener's ingestion records. */
    public static final String SOURCE_SYSTEM = "pos-invoice";

    /**
     * Event type codes this listener records an {@code accounting_event} row for, one per consumed
     * fact (#2433).
     */
    public static final List<String> RECORDED_EVENT_TYPES =
            AccountingEventTypeRegistry.kafkaCodes(AccountingEventTypeRegistry.DOMAIN_INVOICE);

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtInvoiceRepository extInvoiceRepository;
    private final ExtInvoiceTaxRepository extInvoiceTaxRepository;
    private final InvoiceRevenuePostingService invoiceRevenuePostingService;
    private final KafkaFactIngestionRecorder ingestionRecorder;
    private final Counter payloadRejectedCounter;
    private final Counter replicaPersistFailedCounter;

    /** The handler plus its processed mark, or a failure's mark alone, per transaction; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public InvoiceEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtInvoiceRepository extInvoiceRepository,
            ExtInvoiceTaxRepository extInvoiceTaxRepository,
            InvoiceRevenuePostingService invoiceRevenuePostingService,
            KafkaFactIngestionRecorder ingestionRecorder,
            ObjectProvider<MeterRegistry> meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extInvoiceRepository = extInvoiceRepository;
        this.extInvoiceTaxRepository = extInvoiceTaxRepository;
        this.invoiceRevenuePostingService = invoiceRevenuePostingService;
        this.ingestionRecorder = ingestionRecorder;
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", OWNER)
                        .tag("entity", "invoice-events")
                        .register(registry);
        this.replicaPersistFailedCounter = registry == null
                ? null
                : Counter.builder("replica.persist.failed")
                        .description(
                                "Invoice replica writes (ext_invoice or ext_invoice_tax) rejected by the database as a constraint/integrity violation after a well-formed payload was parsed")
                        .tag("owner", OWNER)
                        .tag("entity", "invoice-events")
                        .register(registry);
    }

    @KafkaListener(
            topics = "${pos.accounting.kafka.invoice-events-topic:invoice.events.v1}",
            groupId = "pos-accounting-invoice-events")
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

        // The replica upsert and the GL posting are deliberately separated (#1843): the try/catch
        // below classifies replica failures (malformed payload -> mark processed; integrity /
        // transient -> rethrow), and the generic catch (Exception) at its foot marks the event
        // processed. A GL posting failure (missing mapping, CLOSED period, transient DB error)
        // must never fall into that generic path and be marked processed over a ledger entry that
        // never happened, so posting — which shares the replica upsert's and the processed mark's
        // transaction (#2146) — wraps its failure in RevenuePostingFailure, and the first catch
        // below unwraps and rethrows it for container retry / DLQ (ADR-0044 §4), the same
        // contract OrderEventsListener documents. The transaction rolls back together, replica
        // row included.
        try {
            handlerTransaction.executeWithoutResult(_ -> {
                if (InvoiceUpdatedV1.EVENT_TYPE.equals(eventType)) {
                    InvoiceUpdatedV1 payload =
                            objectMapper.treeToValue(envelope.path("payload"), InvoiceUpdatedV1.class);
                    boolean applied = applyInvoiceUpdate(envelope, payload);
                    try {
                        postAndRecordRevenue(payload, envelope, eventId, applied);
                    } catch (RuntimeException e) {
                        throw new RevenuePostingFailure(e);
                    }
                } else {
                    // Ignored types are still recorded as processed: the owner's manifest counts
                    // every fact in the window (#1537 F1) — pos-invoice's InvoiceEventPublisher
                    // also publishes invoice.billing-rules.updated onto this same topic, and
                    // ManifestPublisher's window count includes it regardless of type.
                    log.debug("Ignoring invoice event type={} eventId={}", eventType, eventId);
                }
                markProcessed(eventId);
            });
        } catch (RevenuePostingFailure e) {
            throw e.getCause();
        } catch (DataIntegrityViolationException e) {
            // A well-formed payload the database still refused as a constraint/integrity
            // violation (e.g. a NOT NULL or unique-key rejection) on either invoice replica table
            // — ext_invoice (applyInvoiceUpdate's saveAndFlush) or ext_invoice_tax
            // (replaceTaxBreakdown's deleteByInvoiceId/saveAll) — distinct from a malformed
            // payload: the replica row for a real fact could not be persisted, so it must be
            // observable (counter + ERROR log) rather than fall into the generic WARN path below
            // and be swallowed (#1651). Other permanent DataAccessExceptions (e.g. a
            // programming error like InvalidDataAccessApiUsageException) are not constraint
            // rejections and keep the pre-existing generic path below, unchanged. Rethrown, same
            // as the RetryableConsumerFailures set below, so the container error handler retries/DLQs
            // it (ADR-0044 §4) instead of marking the event processed over a row the replica
            // never actually got.
            if (replicaPersistFailedCounter != null) {
                replicaPersistFailedCounter.increment();
            }
            log.error(
                    "Database rejected invoice replica write (ext_invoice / ext_invoice_tax) invoiceId={} eventId={} type={}: {}",
                    envelope.path("payload").path("invoiceId").stringValue(null),
                    eventId,
                    e.getClass().getSimpleName(),
                    e.getMessage(),
                    e);
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed invoice event payload eventId={}: {}", eventId, e.getMessage(), e);
            handlerTransaction.executeWithoutResult(_ -> markProcessed(eventId));
        } catch (Exception e) {
            if (RetryableConsumerFailures.isRetryable(e)) {
                // Retry with backoff / DLQ via the container error handler (ADR-0044 §4).
                throw e;
            }
            log.warn("Skipping malformed invoice event eventId={}", eventId, e);
            handlerTransaction.executeWithoutResult(_ -> markProcessed(eventId));
        }
    }

    private void markProcessed(@NonNull String eventId) {
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    /**
     * Carries a GL posting failure out of the handler transaction past the replica catch ladder,
     * so {@link #onInvoiceEvent} can rethrow the original exception unwrapped instead of marking
     * the event processed over a ledger entry that never happened.
     */
    private static final class RevenuePostingFailure extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        RevenuePostingFailure(@NonNull RuntimeException cause) {
            super(cause);
        }

        @Override
        public synchronized @NonNull RuntimeException getCause() {
            return (RuntimeException) super.getCause();
        }
    }

    /**
     * Dispatch the applied (non-stale) fact to invoice revenue recognition (#1843) by status:
     * {@code FINALIZED}/{@code POSTED} recognize, {@code DRAFT}/{@code CANCELLED} reverse an open
     * recognition, anything else ({@code ERROR}, unknown) is left alone; then write the fact's
     * ingestion record (#2433), a stale fact included. Failures propagate — see {@link
     * #onInvoiceEvent}.
     */
    private void postAndRecordRevenue(
            @NonNull InvoiceUpdatedV1 payload, @NonNull JsonNode envelope, @NonNull String eventId, boolean applied) {
        String status = payload.status();
        String occurredAtText = envelope.path("occurredAtUtc").stringValue(null);
        Instant occurredAt = occurredAtText == null ? Instant.now(clock) : Instant.parse(occurredAtText);
        boolean recognizing = InvoiceRevenuePostingService.POSTING_STATUSES.contains(status);
        FactPostingOutcome outcome;
        if (!applied) {
            outcome = FactPostingOutcome.notPostable("Stale invoice fact (aggregateVersion "
                    + envelope.path("aggregateVersion").longValue(0)
                    + " below the replica's) not applied; no revenue entry posted");
        } else if (recognizing) {
            outcome = invoiceRevenuePostingService.postRevenue(payload);
        } else if (InvoiceRevenuePostingService.REVERSING_STATUSES.contains(status)) {
            outcome = invoiceRevenuePostingService.reverseRevenue(payload, occurredAt);
        } else {
            outcome = FactPostingOutcome.notPostable(
                    "Invoice status " + status + " neither recognizes nor reverses revenue; nothing posted");
        }
        // Business time: the revenue entry's own date (finalizedAt) for a recognizing fact, else the
        // fact's occurrence, which is also a reversal entry's date.
        Instant businessTime = recognizing && payload.finalizedAt() != null ? payload.finalizedAt() : occurredAt;
        ingestionRecorder.record(
                SOURCE_SYSTEM,
                InvoiceUpdatedV1.EVENT_TYPE,
                eventId,
                payload.invoiceId(),
                LocalDateTime.ofInstant(businessTime, clock.getZone()),
                payload,
                outcome);
    }

    /**
     * Upsert the replica from the envelope's payload.
     *
     * @return whether it was applied; {@code false} when the event was stale and skipped — the
     *     caller only dispatches applied facts to GL posting
     */
    private boolean applyInvoiceUpdate(JsonNode envelope, InvoiceUpdatedV1 payload) {
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
            return false;
        }

        // saveAndFlush, not save (#1651): ExtInvoice's id is assigned (never generated), so a
        // plain save() only enqueues the write — a DB rejection (e.g. a constraint violation)
        // would otherwise surface at transaction commit, outside this method's try/catch, and
        // bypass the persist-failure counter and ERROR log. Flushing here forces the rejection
        // to happen inside applyInvoiceUpdate, where the caller's catch (DataIntegrityViolationException)
        // can see it.
        extInvoiceRepository.saveAndFlush(ExtInvoice.builder()
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
        return true;
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
