package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.config.AccountingEventTypeRegistry;
import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
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
 * Only {@code order.session.closed} events are handled; the topic's other (high-volume) fact types
 * are ignored without recording their eventIds.
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
 */
@Slf4j
@Component
@KafkaRails
public class OrderEventsListener {

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
            AccountingCalendarZoneResolver zoneResolver) {
        this.zoneResolver = zoneResolver;
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
        if (!RegisterSessionClosedV1.EVENT_TYPE.equals(eventType)) {
            log.debug("Ignoring order event type={}", eventType);
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping register-session-closed event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate register-session-closed event eventId={}", eventId);
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

    private void reject(@NonNull String eventId, DatabindException e) {
        if (payloadRejectedCounter != null) {
            payloadRejectedCounter.increment();
        }
        log.error(
                "Rejected malformed register-session-closed event payload eventId={}: {}", eventId, e.getMessage(), e);
        markInOwnTransaction(eventId);
    }

    private void markInOwnTransaction(@NonNull String eventId) {
        handlerTransaction.executeWithoutResult(_ -> markProcessed(eventId));
    }

    private void markProcessed(@NonNull String eventId) {
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .processedAt(Instant.now(clock))
                .build());
    }
}
