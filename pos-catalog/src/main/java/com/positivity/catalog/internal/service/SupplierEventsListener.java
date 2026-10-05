package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.entity.ProcessedEvent;
import com.positivity.catalog.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.supplier.SupplierCatalogRepublishCompletedV1;
import com.positivity.domainevents.supplier.SupplierCatalogUpdatedV1;
import com.positivity.domainevents.supplier.SupplierPriceCatalogImportCompletedV1;
import com.positivity.domainevents.supplier.SupplierPriceCatalogUpdatedV1;
import com.positivity.kafka.common.KafkaRails;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The single consumer of {@code supplier.events.v1} in pos-catalog: PRICAT imports (ADR-0053) and
 * MKCAT tread-design enrichment (CAP-324, ADR-0044 §6).
 *
 * <h2>Why one consumer and not one per event type</h2>
 *
 * {@code processed_events} is keyed by {@code event_id} alone, and every consumer here records every
 * event it sees — including the types it deliberately ignores, so that the owner's reconciliation
 * manifest counts the whole window rather than reporting ignored facts as missing. Two consumer
 * groups on this topic therefore suppressed each other (#2177): whichever group reached an event
 * first recorded its id, and the group that actually handled that type found the id already present
 * and skipped the work entirely. The PRICAT group recording a {@code supplier.catalog.updated} event
 * as ignored silently dropped the enrichment, intermittently and depending on consumer scheduling.
 *
 * <p>So the topic gets one consumer that dispatches by event type. Adding a new supplier event type
 * means adding a branch here, not a listener elsewhere.
 *
 * <h2>What that race lost stays lost to a replay (#2356)</h2>
 *
 * The guard below is also why the enrichments #2177 dropped cannot be replayed: their ids are in
 * {@code processed_events}, recorded as ignored, and the same id delivered again stops here again.
 * They come back only as <em>new</em> events — pos-supplier's answer to
 * {@code supplier.catalog.republish.requested}, which re-emits every staged variant under a new id
 * and closes with {@code supplier.catalog.republish.completed}. Both types route to
 * {@link SupplierCatalogEnrichmentHandler}.
 *
 * <h2>Who marks what</h2>
 *
 * The de-duplication guard lives here; the mark does not, for the types with a handler. Each
 * handler ({@link SupplierPriceCatalogEventHandler}, {@link SupplierCatalogEnrichmentHandler}) applies
 * the event and records its id in one {@code REQUIRES_NEW} transaction (#2146), so there is no window
 * between an applied event and its mark, and an event that was never applied is never marked. This
 * class records an id itself only for types nobody handles. Those are still recorded so a future
 * owner-manifest reconcile counts the whole window.
 */
@Slf4j
@Component
@KafkaRails
public class SupplierEventsListener {

    /** Producing domain, per the repo-wide processed_events convention. */
    static final String OWNER = "supplier";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final SupplierPriceCatalogEventHandler priceCatalogHandler;
    private final SupplierCatalogEnrichmentHandler enrichmentHandler;

    /** The mark of an ignored event, in a transaction of its own. */
    private final TransactionTemplate handlerTransaction;

    public SupplierEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            SupplierPriceCatalogEventHandler priceCatalogHandler,
            SupplierCatalogEnrichmentHandler enrichmentHandler,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.priceCatalogHandler = priceCatalogHandler;
        this.enrichmentHandler = enrichmentHandler;
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @KafkaListener(
            topics = "${pos.catalog.kafka.supplier-events-topic:supplier.events.v1}",
            groupId = "${pos.catalog.kafka.supplier-events-consumer-group:pos-catalog-supplier-events}")
    public void onSupplierEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable supplier event", e);
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping supplier event without eventId");
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        if (SupplierPriceCatalogUpdatedV1.EVENT_TYPE.equals(eventType)
                || SupplierPriceCatalogImportCompletedV1.EVENT_TYPE.equals(eventType)) {
            priceCatalogHandler.handle(envelope, eventId);
        } else if (SupplierCatalogUpdatedV1.EVENT_TYPE.equals(eventType)
                || SupplierCatalogRepublishCompletedV1.EVENT_TYPE.equals(eventType)) {
            enrichmentHandler.handle(envelope, eventId);
        } else {
            recordIgnored(eventId, eventType);
        }
    }

    /**
     * Nothing is caught here on purpose: a {@link TransientDataAccessException} from the mark
     * propagates for container retry, the same as it does from every handler.
     */
    private void recordIgnored(@NonNull String eventId, String eventType) {
        handlerTransaction.executeWithoutResult(_ -> processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build()));
        log.debug("Ignoring supplier event type={} eventId={}", eventType, eventId);
    }
}
