package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.AccountingEventTypeRegistry;
import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import com.positivity.domainevents.order.RegisterSessionOpenedV1;
import com.positivity.kafka.common.KafkaRails;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code order.events.v1} register-session close facts into over/short GL postings
 * (odoo-parity G3, issue #1083).
 *
 * <p>Same reliability contract as {@link InventoryEventsListener}: idempotent via {@code
 * processed_events}, transient DB errors and posting failures (closed period, missing mapping,
 * anything unexpected) propagate unwrapped and unmarked for container retry / DLQ (ADR-0044 §4),
 * malformed payloads logged and marked processed so a poison record never blocks the partition.
 * Only {@code order.session.closed} and {@code order.session.opened} events are handled.
 *
 * <p><b>Reconciliation (#2579).</b> Every eventId read from the topic is recorded in {@code processed_events}
 * under the {@link #OWNER} tag, the topic's other fact types included: pos-order's {@code order.manifest.v1}
 * counts every fact of a window, and {@link OrderManifestListener} compares it against exactly these rows. A
 * fact type this listener ignores is marked processed and nothing else.
 *
 * <p><b>Transaction shape (ADR-0044 as amended by #2146).</b> The listener method is not
 * transactional: the envelope and payload are parsed and {@code processed_events} checked before
 * any transaction opens; the posting and its processed mark commit together in a {@code
 * REQUIRES_NEW} transaction of their own; a malformed payload is marked in a transaction of its
 * own. A permanent failure thrown through a {@code @Transactional} service therefore rolls back
 * only the handler's work instead of poisoning a shared listener transaction.
 *
 * <p>Posting itself (idempotent on sessionId via posting key, period-gated, accounts resolved
 * through the {@code REGISTER_OVER_SHORT} posting category, zero-variance posts nothing) lives in
 * {@link RegisterOverShortPostingService}.
 *
 * <p><b>Ingestion record (#2433).</b> Every consumed session-close fact writes one {@code
 * accounting_event} row through {@link KafkaFactIngestionRecorder} in the handler transaction:
 * {@code PROCESSED / NEW} linked to the posted entry, {@code PROCESSED / NEW} with no entry for a
 * zero variance, {@code PROCESSED / DUPLICATE_IGNORED} when the session's posting key was already
 * registered. A currency hold writes its own {@code SUSPENDED} row inside the posting service.
 *
 * <p><b>Session replica (#2571, #2573).</b> Both session facts also keep {@link RegisterSessionReplica}, which the
 * register float relocation reads to refuse moving a register with an open session. The opened fact only writes
 * the replica (it posts nothing and records no ingestion row), together with its processed mark. The closed fact
 * closes the replica row in a {@code REQUIRES_NEW} transaction of its own, <em>before</em> the over/short posting
 * transaction, which keeps the processed mark. This shape is deliberate: a close whose posting fails and goes to
 * retry or the DLQ still closes the session, so the register is not left blocked behind a session pos-order has
 * closed. It is safe because the posting never reads the replica or {@code register_float}, and the replica write
 * is state-based and version-guarded, so a redelivery re-applies it harmlessly.
 */
@Slf4j
@Component
@KafkaRails
public class OrderEventsListener {

    /**
     * Owner tag stamped on every {@code processed_events} row this listener writes, scoping {@link
     * OrderManifestListener}'s window scan to {@code order.events.v1} in a table every listener of this
     * module shares (#2579).
     */
    static final String OWNER = "order";

    /**
     * Event type codes this listener records an {@code accounting_event} row for, one per consumed
     * fact (#2433).
     */
    public static final List<String> RECORDED_EVENT_TYPES =
            AccountingEventTypeRegistry.kafkaCodes(AccountingEventTypeRegistry.DOMAIN_ORDER);

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final RegisterOverShortPostingService registerOverShortPostingService;
    private final KafkaFactIngestionRecorder ingestionRecorder;
    private final RegisterSessionReplica sessionReplica;
    private final Counter payloadRejectedCounter;

    private final AccountingCalendarZoneResolver zoneResolver;

    /** The handler plus its processed mark, or a failure's mark alone, per transaction; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public OrderEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            RegisterOverShortPostingService registerOverShortPostingService,
            KafkaFactIngestionRecorder ingestionRecorder,
            ObjectProvider<MeterRegistry> meterRegistry,
            PlatformTransactionManager transactionManager,
            AccountingCalendarZoneResolver zoneResolver,
            RegisterSessionReplica sessionReplica) {
        this.zoneResolver = zoneResolver;
        this.sessionReplica = sessionReplica;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.registerOverShortPostingService = registerOverShortPostingService;
        this.ingestionRecorder = ingestionRecorder;
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", "order")
                        .tag("entity", "order-events")
                        .register(registry);
    }

    @KafkaListener(
            topics = "${pos.accounting.kafka.order-events-topic:order.events.v1}",
            groupId = "pos-accounting-order-events")
    public void onOrderEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable order event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping {} event without eventId: {}", eventType, message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate {} event eventId={}", eventType, eventId);
            return;
        }
        boolean opened = RegisterSessionOpenedV1.EVENT_TYPE.equals(eventType);
        if (!opened && !RegisterSessionClosedV1.EVENT_TYPE.equals(eventType)) {
            // Recorded all the same: the owner's manifest counts every fact of the window (#2579).
            log.debug("Ignoring order event type={} eventId={}", eventType, eventId);
            markInOwnTransaction(eventId);
            return;
        }
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0L);
        if (opened) {
            onSessionOpened(envelope, eventId, aggregateVersion);
            return;
        }

        // Only deserialization failures are terminal for this record — skip and mark processed (in a
        // transaction of its own) so a malformed payload does not poison the partition. Anything
        // thrown by posting (transient DB errors, period gate, unexpected failures) propagates
        // unwrapped and unmarked for container retry / DLQ.
        RegisterSessionClosedV1 fact;
        try {
            fact = objectMapper.treeToValue(envelope.path("payload"), RegisterSessionClosedV1.class);
        } catch (DatabindException e) {
            reject(eventId, e);
            return;
        } catch (Exception e) {
            log.warn("Skipping malformed register-session-closed event eventId={}", eventId, e);
            markInOwnTransaction(eventId);
            return;
        }
        if (fact == null) {
            log.warn("Skipping register-session-closed event without a payload eventId={}", eventId);
            markInOwnTransaction(eventId);
            return;
        }

        try {
            // The replica first, in its own transaction: see the class doc ("Session replica").
            handlerTransaction.executeWithoutResult(_ -> sessionReplica.closed(fact, aggregateVersion));
            handlerTransaction.executeWithoutResult(_ -> {
                FactPostingOutcome outcome = registerOverShortPostingService.postOverShort(fact, eventId);
                ingestionRecorder.record(
                        RegisterOverShortPostingService.SOURCE_SYSTEM,
                        RegisterSessionClosedV1.EVENT_TYPE,
                        eventId,
                        fact.sessionId(),
                        zoneResolver.heldRecordDateTime(fact.closedAt()),
                        fact,
                        outcome);
                markProcessed(eventId);
            });
        } catch (DatabindException e) {
            reject(eventId, e);
        }
    }

    /**
     * {@code order.session.opened} (#2571, #2573): the replica row and the processed mark, in one transaction. A
     * malformed payload is marked processed in its own; a database failure propagates for retry / DLQ.
     */
    private void onSessionOpened(@NonNull JsonNode envelope, @NonNull String eventId, long aggregateVersion) {
        RegisterSessionOpenedV1 fact;
        try {
            fact = objectMapper.treeToValue(envelope.path("payload"), RegisterSessionOpenedV1.class);
        } catch (DatabindException e) {
            reject(eventId, e);
            return;
        } catch (Exception e) {
            log.warn("Skipping malformed register-session-opened event eventId={}", eventId, e);
            markInOwnTransaction(eventId);
            return;
        }
        if (fact == null) {
            log.warn("Skipping register-session-opened event without a payload eventId={}", eventId);
            markInOwnTransaction(eventId);
            return;
        }
        handlerTransaction.executeWithoutResult(_ -> {
            sessionReplica.opened(fact, aggregateVersion);
            markProcessed(eventId);
        });
    }

    private void reject(@NonNull String eventId, DatabindException e) {
        if (payloadRejectedCounter != null) {
            payloadRejectedCounter.increment();
        }
        log.error("Rejected malformed register-session event payload eventId={}: {}", eventId, e.getMessage(), e);
        markInOwnTransaction(eventId);
    }

    private void markInOwnTransaction(@NonNull String eventId) {
        handlerTransaction.executeWithoutResult(_ -> markProcessed(eventId));
    }

    private void markProcessed(@NonNull String eventId) {
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }
}
