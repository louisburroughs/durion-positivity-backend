package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.config.CatalogFactPublisher;
import com.positivity.catalog.internal.config.OutboxEventWriter;
import com.positivity.catalog.internal.entity.ProcessedEvent;
import com.positivity.catalog.internal.entity.SupplierArticleCodeEntity;
import com.positivity.catalog.internal.entity.SupplierPriceEntryEntity;
import com.positivity.catalog.internal.entity.SupplierPriceImportChunkEntity;
import com.positivity.catalog.internal.entity.SupplierPriceImportEntity;
import com.positivity.catalog.internal.repository.ProcessedEventRepository;
import com.positivity.catalog.internal.repository.SupplierArticleCodeRepository;
import com.positivity.catalog.internal.repository.SupplierPriceEntryRepository;
import com.positivity.catalog.internal.repository.SupplierPriceImportChunkRepository;
import com.positivity.catalog.internal.repository.SupplierPriceImportRepository;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.supplier.SupplierPriceCatalogImportCompletedV1;
import com.positivity.domainevents.supplier.SupplierPriceCatalogLine;
import com.positivity.domainevents.supplier.SupplierPriceCatalogRepublishRequestedV1;
import com.positivity.domainevents.supplier.SupplierPriceCatalogUpdatedV1;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
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
 * Applies vendor PRICAT imports from {@code supplier.events.v1} into append-only supplier price
 * entries (ADR-0053 §1–§2, §7).
 *
 * <p>pos-supplier owns the received document; pos-catalog owns the business fact. This listener is
 * the join between them, and it is the only writer of {@code supplier_price_entry}.
 *
 * <h2>Idempotency and ordering</h2>
 *
 * Two guards, because they answer different questions. {@code processed_events} makes a redelivery
 * of the <em>same event</em> a no-op. The applied-chunk log
 * ({@code supplier_price_import_chunk}) makes a redelivery of the <em>same chunk</em> a no-op, which
 * the event-id guard cannot do: a re-emit requested after a detected gap republishes the chunks as
 * new events with new ids, and without the second guard that recovery would write a duplicate copy
 * of every line it re-delivers.
 *
 * <p>Chunks are independent of one another — each carries its own lines — so they may be applied in
 * any order, and the completion event is the only thing that needs the others to have landed first.
 * Completeness is therefore a comparison of the applied-chunk set against the declared total, not
 * an ordering assumption.
 *
 * <h2>Nothing here deletes</h2>
 *
 * An import that reports {@code EMPTY}, or one that fails upstream and never publishes chunks,
 * removes nothing: yesterday's entries remain the latest applicable prices. A vendor outage is not
 * a statement that a catalog is empty, and an append-only table is what makes that structural
 * rather than a rule someone has to remember.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.catalog.kafka", name = "enabled", havingValue = "true")
public class SupplierPriceCatalogEventsListener {

    /** Producing domain, per the repo-wide processed_events convention. */
    static final String OWNER = "supplier";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final SupplierPriceEntryRepository priceEntryRepository;
    private final SupplierPriceImportRepository priceImportRepository;
    private final SupplierPriceImportChunkRepository priceImportChunkRepository;
    private final SupplierArticleCodeRepository supplierArticleCodeRepository;
    private final CatalogFactPublisher catalogFactPublisher;
    private final OutboxEventWriter outboxEventWriter;

    @KafkaListener(
            topics = "${pos.catalog.kafka.supplier-events-topic:supplier.events.v1}",
            groupId = "${pos.catalog.kafka.supplier-events-consumer-group:pos-catalog-supplier-events}")
    @Transactional
    public void onSupplierEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable supplier event", e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping supplier event without eventId");
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            if (SupplierPriceCatalogUpdatedV1.EVENT_TYPE.equals(eventType)) {
                applyChunk(envelope);
            } else if (SupplierPriceCatalogImportCompletedV1.EVENT_TYPE.equals(eventType)) {
                applyCompletion(envelope);
            } else {
                // Ignored types still record their eventId so the owner's manifest reconciles
                // rather than reporting facts this module deliberately skipped as missing.
                log.debug("Ignoring supplier event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            // Rethrown for container retry. Recording this as processed would lose an import's
            // worth of prices with no way to notice: the rows would simply never exist.
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed supplier event eventId={} type={}", eventId, eventType, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyChunk(JsonNode envelope) {
        SupplierPriceCatalogUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), SupplierPriceCatalogUpdatedV1.class);

        SupplierPriceImportEntity tracker = priceImportRepository.save(
                trackerFor(payload.importManifestId(), payload.vendorProfileId(), payload.supplierRef()));

        if (priceImportChunkRepository.existsByImportManifestIdAndChunkSequence(
                payload.importManifestId(), payload.chunkSequence())) {
            // Already applied — almost certainly a re-emit after a gap was reported. Writing the
            // lines again would duplicate them, and counting the chunk again could declare an
            // import complete that still has a hole elsewhere.
            log.debug(
                    "Skipping already-applied PRICAT chunk {} of import {}",
                    payload.chunkSequence(),
                    payload.importManifestId());
            return;
        }

        for (SupplierPriceCatalogLine line : payload.lines()) {
            priceEntryRepository.save(SupplierPriceEntryEntity.builder()
                    .vendorProfileId(payload.vendorProfileId())
                    .supplierRef(payload.supplierRef())
                    .productId(line.productId())
                    .matchMethod(line.matchMethod())
                    .articleEan(line.articleEan())
                    .supplierArticleCode(line.supplierArticleCode())
                    .xReferenceCode(line.xReferenceCode())
                    .buyerAccountNumber(payload.buyerAccountNumber())
                    .countryCode(payload.countryCode())
                    .currency(payload.currency())
                    .suggestedRetailPrice(line.suggestedRetailPrice())
                    .grossPrice(line.grossPrice())
                    .netPrice(line.netPrice())
                    .taxRate(line.taxRate())
                    .recyclingFee(line.recyclingFee())
                    .effectiveFrom(line.effectiveFrom())
                    .sourceDocumentId(payload.sourceDocumentId())
                    .sourceDocumentDate(payload.sourceDocumentDate())
                    .importManifestId(payload.importManifestId())
                    .fetchedAt(payload.fetchedAt())
                    .build());

            if (line.supplierArticleCode() != null
                    && !line.supplierArticleCode().isBlank()) {
                applySupplierArticleCode(payload.vendorProfileId(), payload.supplierRef(), line);
            }
        }

        priceImportChunkRepository.save(SupplierPriceImportChunkEntity.builder()
                .importManifestId(payload.importManifestId())
                .chunkSequence(payload.chunkSequence())
                .linesApplied(payload.lines().size())
                .appliedAt(Instant.now(clock))
                .build());

        tracker.setChunksApplied(tracker.getChunksApplied() + 1);
        tracker.setLinesApplied(tracker.getLinesApplied() + payload.lines().size());
        // The chunk knows the total too; recording it here means a completion event that never
        // arrives still leaves the gap visible.
        tracker.setExpectedChunkCount(payload.chunkCount());
        evaluateCompleteness(tracker);
        priceImportRepository.save(tracker);

        log.debug(
                "Applied PRICAT chunk {}/{} of import {} ({} lines)",
                payload.chunkSequence(),
                payload.chunkCount(),
                payload.importManifestId(),
                payload.lines().size());
    }

    /**
     * Upserts the current-state vendor-article-code row and publishes it, but only when the code
     * (or the vendor's alias snapshot) actually changed — a reimport that restates the same code for
     * every line every cycle should not republish a fact nothing about.
     */
    private void applySupplierArticleCode(UUID vendorProfileId, String supplierRef, SupplierPriceCatalogLine line) {
        SupplierArticleCodeEntity existing = supplierArticleCodeRepository
                .findByVendorProfileIdAndProductId(vendorProfileId, line.productId())
                .orElse(null);
        if (existing != null
                && line.supplierArticleCode().equals(existing.getSupplierArticleCode())
                && supplierRef.equals(existing.getSupplierRef())) {
            return;
        }
        SupplierArticleCodeEntity row = existing != null ? existing : new SupplierArticleCodeEntity();
        row.setVendorProfileId(vendorProfileId);
        row.setSupplierRef(supplierRef);
        row.setProductId(line.productId());
        row.setSupplierArticleCode(line.supplierArticleCode());
        row = supplierArticleCodeRepository.save(row);
        catalogFactPublisher.publishSupplierArticleCodeUpdated(row);
    }

    private void applyCompletion(JsonNode envelope) {
        SupplierPriceCatalogImportCompletedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), SupplierPriceCatalogImportCompletedV1.class);

        SupplierPriceImportEntity tracker =
                trackerFor(payload.importManifestId(), payload.vendorProfileId(), payload.supplierRef());
        tracker.setExpectedChunkCount(payload.chunkCount());
        tracker.setExpectedLineCount(payload.linesMatched());
        tracker.setContentChecksum(payload.contentChecksum());
        tracker.setCompletedAt(Instant.now(clock));
        evaluateCompleteness(tracker);
        priceImportRepository.save(tracker);

        if (SupplierPriceImportEntity.STATUS_INCOMPLETE.equals(tracker.getStatus())) {
            requestRepublish(tracker);
        } else {
            log.info(
                    "PRICAT import {} applied complete: {} chunks, {} lines",
                    payload.importManifestId(),
                    tracker.getChunksApplied(),
                    tracker.getLinesApplied());
        }
    }

    private SupplierPriceImportEntity trackerFor(UUID importManifestId, UUID vendorProfileId, String supplierRef) {
        return priceImportRepository
                .findById(importManifestId)
                .orElseGet(() -> SupplierPriceImportEntity.builder()
                        .importManifestId(importManifestId)
                        .vendorProfileId(vendorProfileId)
                        .supplierRef(supplierRef)
                        .status(SupplierPriceImportEntity.STATUS_APPLYING)
                        .build());
    }

    /**
     * An import is complete when the producer has declared a chunk total and this module has applied
     * that many distinct chunks. Distinct is what the applied-chunk log buys: the counter now counts
     * chunks rather than deliveries, so it can neither be inflated by a re-emit nor fall short
     * because a redelivery was skipped.
     */
    private void evaluateCompleteness(SupplierPriceImportEntity tracker) {
        Integer expected = tracker.getExpectedChunkCount();
        if (expected == null) {
            tracker.setStatus(SupplierPriceImportEntity.STATUS_APPLYING);
            return;
        }
        if (tracker.getCompletedAt() == null && tracker.getChunksApplied() < expected) {
            // Chunks still arriving and no completion event yet: not a gap, just in flight.
            tracker.setStatus(SupplierPriceImportEntity.STATUS_APPLYING);
            return;
        }
        tracker.setStatus(
                tracker.getChunksApplied() >= expected
                        ? SupplierPriceImportEntity.STATUS_COMPLETE
                        : SupplierPriceImportEntity.STATUS_INCOMPLETE);
    }

    private void requestRepublish(SupplierPriceImportEntity tracker) {
        int expected = tracker.getExpectedChunkCount() == null ? 0 : tracker.getExpectedChunkCount();
        log.warn(
                "PRICAT import {} incomplete: applied {} of {} chunks — requesting re-emit",
                tracker.getImportManifestId(),
                tracker.getChunksApplied(),
                expected);

        SupplierPriceCatalogRepublishRequestedV1 request = new SupplierPriceCatalogRepublishRequestedV1(
                tracker.getImportManifestId(),
                tracker.getVendorProfileId(),
                tracker.getChunksApplied(),
                expected,
                "pos-catalog",
                "applied " + tracker.getChunksApplied() + " of " + expected + " chunks");

        outboxEventWriter.publish(
                DomainTopics.commands(OWNER),
                new DomainEventEnvelope<>(
                        UUIDv7Generator.generate(),
                        SupplierPriceCatalogRepublishRequestedV1.EVENT_TYPE,
                        SupplierPriceCatalogRepublishRequestedV1.SCHEMA_VERSION,
                        tracker.getImportManifestId(),
                        0L,
                        Instant.now(clock),
                        "pos-catalog",
                        null,
                        null,
                        request));
    }
}
