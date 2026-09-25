package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.inventory.InventoryAdjustedV1;
import com.positivity.domainevents.inventory.ProductValueChangedV1;
import com.positivity.domainevents.inventory.ScrapPostedV1;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
 * Consumes {@code inventory.events.v1} posting facts into GL postings: a dispatcher on
 * {@code eventType}.
 *
 * <ul>
 *   <li>{@code inventory.scrap.posted} ({@link ScrapPostedV1}) → {@link InventoryShrinkagePostingService}
 *       (odoo-parity D2, issue #1043);
 *   <li>{@code inventory.adjustment.posted} ({@link InventoryAdjustedV1}, cycle-count and manual
 *       adjustments) → {@link InventoryAdjustmentPostingService} (issue #2191);
 *   <li>{@code inventory.product-value.changed} ({@link ProductValueChangedV1}, manual cost
 *       revaluation) → {@link InventoryRevaluationPostingService} (issue #2193); a zero value delta
 *       posts no journal entry but still records the fact {@code PROCESSED} (never {@code SKIPPED}
 *       — there is no uncosted case for a revaluation, {@code totalValueDelta} is always computed);
 *   <li>every other type on the topic (high-volume snapshots) is ignored without recording its
 *       eventId.
 * </ul>
 *
 * <p><b>Transaction shape (ADR-0044 as amended by #2146).</b> The listener method is not
 * {@code @Transactional}. The envelope and payload are parsed and {@code processed_events} checked
 * before any transaction opens. The posting, its posting key, the ingestion record and the
 * processed mark then commit together in a {@code REQUIRES_NEW} transaction of their own. Outcomes:
 *
 * <ul>
 *   <li>applied — journal entry, posting key, {@code PROCESSED} ingestion record (outcome
 *       {@code NEW}) and mark, atomically; {@code accounting.inventory.fact.posted{eventType}}++;
 *   <li>duplicate {@code eventId} — dropped before any transaction;
 *   <li>duplicate posting key (a re-emitted fact) — nothing posted; {@code PROCESSED} ingestion
 *       record (outcome {@code DUPLICATE_IGNORED}) and mark in the handler transaction;
 *   <li>unparsable payload / {@link DatabindException} — {@code replica.payload.rejected}++, marked
 *       in a transaction of its own so a poison record never blocks the partition;
 *   <li>uncosted ({@code unitCost} null or non-positive, ADR-0048 {@code costSource = NONE}) — never
 *       posted: {@code SKIPPED} ingestion record ({@code UNCOSTED_FACT}) and mark in a transaction
 *       of their own, {@code accounting.inventory.fact.skipped{eventType, reason=UNCOSTED}}++. The
 *       skip is terminal: the fact carries the cost at posting time and a later cost is a different
 *       fact;
 *   <li>{@code PERIOD_CLOSED}, {@code PERIOD_HARD_LOCKED}, {@code MAPPING_NOT_FOUND}, a
 *       {@code TransientDataAccessException} or anything unexpected — propagates unmarked for
 *       container retry and then {@code inventory.events.v1.dlq}, to be replayed after the
 *       operations fix.
 * </ul>
 *
 * <p>A permanent failure thrown through a {@code @Transactional} service therefore rolls back only
 * the handler's work, instead of poisoning a shared listener transaction whose commit would throw
 * {@code UnexpectedRollbackException}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.accounting.kafka", name = "enabled", havingValue = "true")
public class InventoryEventsListener {

    static final String POSTED_METRIC = "accounting.inventory.fact.posted";
    static final String SKIPPED_METRIC = "accounting.inventory.fact.skipped";
    static final String SKIP_REASON_UNCOSTED = "UNCOSTED";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final InventoryShrinkagePostingService shrinkagePostingService;
    private final InventoryAdjustmentPostingService adjustmentPostingService;
    private final InventoryRevaluationPostingService revaluationPostingService;
    private final InventoryFactIngestionRecorder ingestionRecorder;
    private final @Nullable MeterRegistry meterRegistry;
    private final @Nullable Counter payloadRejectedCounter;

    /** The handler plus its processed mark, or a failure's mark alone, per transaction; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public InventoryEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            InventoryShrinkagePostingService shrinkagePostingService,
            InventoryAdjustmentPostingService adjustmentPostingService,
            InventoryRevaluationPostingService revaluationPostingService,
            InventoryFactIngestionRecorder ingestionRecorder,
            ObjectProvider<MeterRegistry> meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.shrinkagePostingService = shrinkagePostingService;
        this.adjustmentPostingService = adjustmentPostingService;
        this.revaluationPostingService = revaluationPostingService;
        this.ingestionRecorder = ingestionRecorder;
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.meterRegistry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = this.meterRegistry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", "inventory")
                        .tag("entity", "inventory-events")
                        .register(this.meterRegistry);
    }

    @KafkaListener(
            topics = "${pos.accounting.kafka.inventory-events-topic:inventory.events.v1}",
            groupId = "pos-accounting-inventory-events")
    public void onInventoryEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable inventory event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        if (!ScrapPostedV1.EVENT_TYPE.equals(eventType)
                && !InventoryAdjustedV1.EVENT_TYPE.equals(eventType)
                && !ProductValueChangedV1.EVENT_TYPE.equals(eventType)) {
            log.debug("Ignoring inventory event type={}", eventType);
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping {} event without eventId: {}", eventType, message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate {} event eventId={}", eventType, eventId);
            return;
        }

        if (ScrapPostedV1.EVENT_TYPE.equals(eventType)) {
            onScrapPosted(eventId, envelope);
        } else if (InventoryAdjustedV1.EVENT_TYPE.equals(eventType)) {
            onAdjustmentPosted(eventId, envelope);
        } else {
            onRevaluationPosted(eventId, envelope);
        }
    }

    private void onScrapPosted(String eventId, JsonNode envelope) {
        String eventType = ScrapPostedV1.EVENT_TYPE;
        ScrapPostedV1 fact = readPayload(eventType, eventId, envelope, ScrapPostedV1.class);
        if (fact == null) {
            return;
        }
        LocalDateTime transactionDate = businessDate(fact.occurredAt());
        if (isUncosted(fact.unitCost())) {
            String detail = "Uncosted scrap fact — no shrinkage JE posted (scrapId " + fact.scrapId() + ", sku "
                    + fact.sku() + ", quantity " + fact.quantity() + ", reasonCode " + fact.reasonCode()
                    + ", costSource " + fact.costSource() + ", unitCost " + fact.unitCost() + ")";
            log.warn("Skipping uncosted scrap fact | eventId={} | {}", eventId, detail);
            skipUncosted(eventType, eventId, fact.scrapId(), transactionDate, fact, detail);
            return;
        }
        post(
                eventType,
                eventId,
                () -> shrinkagePostingService.postShrinkage(fact),
                fact.scrapId(),
                transactionDate,
                fact,
                InventoryShrinkagePostingService.toSourceEventId(fact.scrapId()));
    }

    private void onAdjustmentPosted(String eventId, JsonNode envelope) {
        String eventType = InventoryAdjustedV1.EVENT_TYPE;
        InventoryAdjustedV1 fact = readPayload(eventType, eventId, envelope, InventoryAdjustedV1.class);
        if (fact == null) {
            return;
        }
        LocalDateTime transactionDate = businessDate(fact.occurredAt());
        if (isUncosted(fact.unitCost())) {
            String detail = "Uncosted adjustment fact — no adjustment JE posted (" + fact.adjustmentKind() + " "
                    + fact.adjustmentId() + ", sku " + fact.sku() + ", quantityDelta "
                    + fact.quantityDelta().toPlainString() + ", reasonCode " + fact.reasonCode() + ", costSource "
                    + fact.costSource() + ", unitCost " + fact.unitCost() + ")";
            log.warn("Skipping uncosted adjustment fact | eventId={} | {}", eventId, detail);
            skipUncosted(eventType, eventId, fact.adjustmentId(), transactionDate, fact, detail);
            return;
        }
        post(
                eventType,
                eventId,
                () -> adjustmentPostingService.postAdjustment(fact),
                fact.adjustmentId(),
                transactionDate,
                fact,
                InventoryAdjustmentPostingService.toSourceEventId(fact.adjustmentKind(), fact.adjustmentId()));
    }

    /**
     * A revaluation fact never carries an uncosted case ({@code totalValueDelta} is always
     * computed), so it always reaches {@link InventoryRevaluationPostingService#postRevaluation}.
     * A zero delta posts no journal entry but is still recorded {@code PROCESSED} — never
     * {@code SKIPPED} — since it is not a data-quality gap, just nothing to post.
     */
    private void onRevaluationPosted(String eventId, JsonNode envelope) {
        String eventType = ProductValueChangedV1.EVENT_TYPE;
        ProductValueChangedV1 fact = readPayload(eventType, eventId, envelope, ProductValueChangedV1.class);
        if (fact == null) {
            return;
        }
        LocalDateTime transactionDate = businessDate(fact.occurredAt());
        post(
                eventType,
                eventId,
                () -> revaluationPostingService.postRevaluation(fact),
                fact.revaluationId(),
                transactionDate,
                fact,
                InventoryRevaluationPostingService.toSourceEventId(fact.revaluationId()));
    }

    /**
     * Deserialize the payload before any transaction. A malformed payload is permanent for this
     * record: counted and marked processed in a transaction of its own; returns {@code null}.
     */
    private <T> @Nullable T readPayload(String eventType, String eventId, JsonNode envelope, Class<T> type) {
        T fact;
        try {
            fact = objectMapper.treeToValue(envelope.path("payload"), type);
        } catch (DatabindException e) {
            reject(eventType, eventId, e);
            return null;
        } catch (Exception e) {
            log.warn("Skipping malformed {} event eventId={}", eventType, eventId, e);
            markInOwnTransaction(eventId);
            return null;
        }
        if (fact == null) {
            log.warn("Skipping {} event without a payload eventId={}", eventType, eventId);
            markInOwnTransaction(eventId);
        }
        return fact;
    }

    /**
     * Post, record and mark in one handler transaction. Anything but a {@link DatabindException}
     * propagates unmarked for container retry and DLQ.
     */
    private void post(
            String eventType,
            String eventId,
            Supplier<@Nullable UUID> posting,
            UUID domainKeyId,
            LocalDateTime transactionDate,
            Object fact,
            UUID sourceEventId) {
        UUID posted;
        try {
            posted = handlerTransaction.execute(_ -> {
                UUID journalEntryId = posting.get();
                ingestionRecorder.recordPosted(
                        eventType, eventId, domainKeyId, transactionDate, fact, journalEntryId, sourceEventId);
                markProcessed(eventId);
                return journalEntryId;
            });
        } catch (DatabindException e) {
            reject(eventType, eventId, e);
            return;
        }
        if (posted != null && meterRegistry != null) {
            Counter.builder(POSTED_METRIC)
                    .description("Inventory posting facts posted to the GL")
                    .tag("eventType", eventType)
                    .register(meterRegistry)
                    .increment();
        }
    }

    /** Uncosted: never post; the SKIPPED ingestion record and the mark commit together. */
    private void skipUncosted(
            String eventType,
            String eventId,
            UUID domainKeyId,
            LocalDateTime transactionDate,
            Object fact,
            String detail) {
        try {
            handlerTransaction.executeWithoutResult(_ -> {
                ingestionRecorder.recordUncostedSkip(eventType, eventId, domainKeyId, transactionDate, fact, detail);
                markProcessed(eventId);
            });
        } catch (DatabindException e) {
            reject(eventType, eventId, e);
            return;
        }
        if (meterRegistry != null) {
            Counter.builder(SKIPPED_METRIC)
                    .description("Inventory posting facts consumed but deliberately not posted")
                    .tag("eventType", eventType)
                    .tag("reason", SKIP_REASON_UNCOSTED)
                    .register(meterRegistry)
                    .increment();
        }
    }

    private void reject(String eventType, String eventId, DatabindException e) {
        if (payloadRejectedCounter != null) {
            payloadRejectedCounter.increment();
        }
        log.error("Rejected malformed {} event payload eventId={}: {}", eventType, eventId, e.getMessage(), e);
        markInOwnTransaction(eventId);
    }

    private static boolean isUncosted(@Nullable BigDecimal unitCost) {
        return unitCost == null || unitCost.signum() <= 0;
    }

    /** Business time, in the zone the posting services use, so the record and the entry agree. */
    private LocalDateTime businessDate(Instant occurredAt) {
        return LocalDateTime.ofInstant(occurredAt, clock.getZone());
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
