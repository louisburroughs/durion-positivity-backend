package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.entity.WarrantyReimbursementExpectation;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.internal.repository.WarrantyReimbursementExpectationRepository;
import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.warranty.WarrantyReimbursementResolvedV1;
import com.positivity.domainevents.warranty.WarrantyReimbursementSubmittedV1;
import com.positivity.tenancy.kafka.RetryableConsumerFailures;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code warranty.events.v1} into accounting's {@code warranty_reimbursement_expectation}
 * expected-credit records (issue #927).
 *
 * <p>Same contract as {@link InvoiceEventsListener}: idempotent via {@code processed_events} in
 * the upsert transaction, stale envelopes (aggregateVersion strictly below the row's) skipped,
 * transient DB errors rethrown for container retry/DLQ, malformed payloads logged and skipped.
 * The topic also carries claim-lifecycle facts ({@code warranty.claim.settled},
 * {@code warranty.claim.snapshot}, part-return facts) this module ignores — their eventIds are
 * still recorded so redelivery stays cheap.
 *
 * <p>The stale guard is {@link ReplicaVersionGuard} (#1486): pos-warranty's
 * {@code ReimbursementServiceImpl} flushes — and even force-increments — the claim's JPA
 * {@code @Version} to stay strictly monotonic, so an equal version applies rather than skips: it
 * is an idempotent no-op for live traffic, and it is what would let a regenerate-from-state replay
 * (the catalog/vehicle {@code facts/replay} pattern, should pos-warranty grow one) repair a row
 * that holds the version number but wrong or missing data.
 *
 * <p><b>Transaction shape (#2146).</b> Same as {@link InvoiceEventsListener}: the upsert and its
 * processed mark commit together in a {@code REQUIRES_NEW} transaction of their own, so a permanent
 * failure rolls back only that work and is recorded in a separate transaction; transient failures
 * still propagate for container retry, unrecorded.
 *
 * <p><b>Ingestion record (#2433).</b> Every consumed reimbursement fact ({@link
 * #RECORDED_EVENT_TYPES}) writes one {@code accounting_event} row through {@link
 * KafkaFactIngestionRecorder} in the same transaction, keyed on the reimbursement id (source system
 * {@value #SOURCE_SYSTEM}): an expectation row posts no journal entry, so an applied fact is {@code
 * PROCESSED / NEW} with no entry, and a stale one {@code SKIPPED / NOT_POSTABLE}. The topic's other
 * facts write no row.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.accounting.kafka", name = "enabled", havingValue = "true")
public class WarrantyEventsListener {

    /** Producing module, stamped as {@code sourceSystem} on this listener's ingestion records. */
    public static final String SOURCE_SYSTEM = "pos-warranty";

    /**
     * Event type codes this listener records an {@code accounting_event} row for, one per consumed
     * fact (#2433).
     */
    public static final List<String> RECORDED_EVENT_TYPES =
            List.of(WarrantyReimbursementSubmittedV1.EVENT_TYPE, WarrantyReimbursementResolvedV1.EVENT_TYPE);

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final WarrantyReimbursementExpectationRepository expectationRepository;
    private final KafkaFactIngestionRecorder ingestionRecorder;
    private final Counter payloadRejectedCounter;

    /** The handler plus its processed mark, or a failure's mark alone, per transaction; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public WarrantyEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            WarrantyReimbursementExpectationRepository expectationRepository,
            KafkaFactIngestionRecorder ingestionRecorder,
            ObjectProvider<MeterRegistry> meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.expectationRepository = expectationRepository;
        this.ingestionRecorder = ingestionRecorder;
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", "warranty")
                        .tag("entity", "warranty-events")
                        .register(registry);
    }

    @KafkaListener(
            topics = "${pos.accounting.kafka.warranty-events-topic:warranty.events.v1}",
            groupId = "pos-accounting-warranty-events")
    public void onWarrantyEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable warranty event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping warranty event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate warranty event eventId={}", eventId);
            return;
        }

        try {
            handlerTransaction.executeWithoutResult(_ -> {
                if (WarrantyReimbursementSubmittedV1.EVENT_TYPE.equals(eventType)) {
                    applyReimbursementSubmitted(envelope, eventId);
                } else if (WarrantyReimbursementResolvedV1.EVENT_TYPE.equals(eventType)) {
                    applyReimbursementResolved(envelope, eventId);
                } else {
                    // Ignored types are still recorded as processed below.
                    log.debug("Ignoring warranty event type={} eventId={}", eventType, eventId);
                }
                markProcessed(eventId);
            });
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed warranty event payload eventId={}: {}", eventId, e.getMessage(), e);
            handlerTransaction.executeWithoutResult(_ -> markProcessed(eventId));
        } catch (Exception e) {
            if (RetryableConsumerFailures.isRetryable(e)) {
                // Retry with backoff / DLQ via the container error handler (ADR-0044 §4).
                throw e;
            }
            log.warn("Skipping malformed warranty event eventId={}", eventId, e);
            handlerTransaction.executeWithoutResult(_ -> markProcessed(eventId));
        }
    }

    private void markProcessed(@NonNull String eventId) {
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyReimbursementSubmitted(JsonNode envelope, String eventId) {
        WarrantyReimbursementSubmittedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), WarrantyReimbursementSubmittedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);

        WarrantyReimbursementExpectation existing =
                expectationRepository.findById(payload.reimbursementId()).orElse(null);
        if (isStale(existing, aggregateVersion, payload.reimbursementId().toString())) {
            record(
                    WarrantyReimbursementSubmittedV1.EVENT_TYPE,
                    eventId,
                    payload.reimbursementId(),
                    payload.submittedAt(),
                    payload,
                    stale(aggregateVersion));
            return;
        }

        expectationRepository.save(WarrantyReimbursementExpectation.builder()
                .reimbursementId(payload.reimbursementId())
                .claimId(payload.claimId())
                .claimCode(payload.claimCode())
                .providerId(payload.providerId())
                .apVendorId(payload.apVendorId())
                .amountRequested(payload.amountRequested())
                .vendorClaimReference(payload.vendorClaimReference())
                .status(WarrantyReimbursementExpectation.STATUS_EXPECTED)
                .submittedAt(payload.submittedAt())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        record(
                WarrantyReimbursementSubmittedV1.EVENT_TYPE,
                eventId,
                payload.reimbursementId(),
                payload.submittedAt(),
                payload,
                FactPostingOutcome.nothingToPost());
        log.info(
                "Recorded warranty reimbursement expectation reimbursementId={} claimCode={} version={}",
                payload.reimbursementId(),
                payload.claimCode(),
                aggregateVersion);
    }

    private void applyReimbursementResolved(JsonNode envelope, String eventId) {
        WarrantyReimbursementResolvedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), WarrantyReimbursementResolvedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);

        WarrantyReimbursementExpectation existing =
                expectationRepository.findById(payload.reimbursementId()).orElse(null);
        if (isStale(existing, aggregateVersion, payload.reimbursementId().toString())) {
            record(
                    WarrantyReimbursementResolvedV1.EVENT_TYPE,
                    eventId,
                    payload.reimbursementId(),
                    payload.resolvedAt(),
                    payload,
                    stale(aggregateVersion));
            return;
        }

        // Upsert-tolerant: if the submitted fact was missed, create the row from what the
        // resolution carries (amountRequested/submittedAt stay null — pos-warranty remains the
        // system of record for the full lifecycle).
        WarrantyReimbursementExpectation.WarrantyReimbursementExpectationBuilder builder = existing != null
                ? existing.toBuilder()
                : WarrantyReimbursementExpectation.builder()
                        .reimbursementId(payload.reimbursementId())
                        .claimId(payload.claimId())
                        .claimCode(payload.claimCode())
                        .providerId(payload.providerId())
                        .apVendorId(payload.apVendorId());
        expectationRepository.save(builder.status(payload.status())
                .amountApproved(payload.amountApproved())
                .creditReference(payload.creditReference())
                .resolvedAt(payload.resolvedAt())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        record(
                WarrantyReimbursementResolvedV1.EVENT_TYPE,
                eventId,
                payload.reimbursementId(),
                payload.resolvedAt(),
                payload,
                FactPostingOutcome.nothingToPost());
        log.info(
                "Resolved warranty reimbursement expectation reimbursementId={} status={} version={}",
                payload.reimbursementId(),
                payload.status(),
                aggregateVersion);
    }

    private void record(
            String eventType,
            String eventId,
            UUID reimbursementId,
            Instant businessTime,
            Object fact,
            FactPostingOutcome outcome) {
        ingestionRecorder.record(
                SOURCE_SYSTEM,
                eventType,
                eventId,
                reimbursementId,
                LocalDateTime.ofInstant(businessTime == null ? Instant.now(clock) : businessTime, clock.getZone()),
                fact,
                outcome);
    }

    private static FactPostingOutcome stale(long aggregateVersion) {
        return FactPostingOutcome.notPostable("Stale warranty reimbursement fact (aggregateVersion " + aggregateVersion
                + " below the expectation row's) not applied");
    }

    private boolean isStale(WarrantyReimbursementExpectation existing, long aggregateVersion, String reimbursementId) {
        // Claim aggregate versions are strictly increasing per emitted fact (mirrors
        // InvoiceEventsListener). Strictly-newer-only skip: equal versions APPLY (#1486,
        // ReplicaVersionGuard) — equal means identical content, and replay resends the held
        // version deliberately to repair wrong or missing rows.
        if (existing != null && ReplicaVersionGuard.isStale(existing.getAggregateVersion(), aggregateVersion)) {
            log.debug(
                    "Skipping stale warranty reimbursement event reimbursementId={} eventVersion={} rowVersion={}",
                    reimbursementId,
                    aggregateVersion,
                    existing.getAggregateVersion());
            return true;
        }
        return false;
    }
}
