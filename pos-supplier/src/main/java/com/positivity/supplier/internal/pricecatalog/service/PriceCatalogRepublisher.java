package com.positivity.supplier.internal.pricecatalog.service;

import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.supplier.SupplierPriceCatalogImportCompletedV1;
import com.positivity.domainevents.supplier.SupplierPriceCatalogLine;
import com.positivity.domainevents.supplier.SupplierPriceCatalogRepublishRequestedV1;
import com.positivity.domainevents.supplier.SupplierPriceCatalogUpdatedV1;
import com.positivity.supplier.internal.entity.PriceCatalogEntryEntity;
import com.positivity.supplier.internal.entity.PriceCatalogImportEntity;
import com.positivity.supplier.internal.enums.PriceCatalogImportStatus;
import com.positivity.supplier.internal.repository.PriceCatalogEntryRepository;
import com.positivity.supplier.internal.repository.PriceCatalogImportRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-emits a completed import's events when a consumer reports it applied fewer chunks than the
 * import declared (ADR-0044 §4).
 *
 * <h2>Why the owner re-emits rather than the consumer reads</h2>
 *
 * A consumer that is short of chunks cannot fetch the lines it missed: ADR-0044 R1 forbids the
 * synchronous read that would make that possible, and R6 makes this module the only owner of what
 * the vendor sent. So recovery is a request on the owner's command topic and a re-publication on
 * the owner's event topic — the same path the original import took, which is what makes the
 * recovered state indistinguishable from the state that should have existed.
 *
 * <h2>Over-broad on purpose</h2>
 *
 * The request names the import, not the missing chunks, because the consumer cannot know what it
 * never received — only how many chunks it is short. So the whole import is re-emitted. That is
 * safe in the one direction that matters: a consumer that already applied a chunk skips it on its
 * applied-chunk log, whereas re-emitting too little would leave the gap that prompted the request.
 *
 * <h2>Bounded, because the request repeats</h2>
 *
 * Serving a request does not guarantee the consumer recovers; if it stays short it will ask again
 * on its next completion event. Left unbounded, a consumer broken for any other reason would drive
 * a re-publication of an entire vendor catalogue in a loop. A cooldown collapses a burst of
 * requests for the same import into one re-emit, and an attempt cap stops the loop outright, loudly
 * — a stuck import an operator can see beats a broker quietly drowning.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceCatalogRepublisher {

    private final PriceCatalogImportRepository importRepository;
    private final PriceCatalogEntryRepository entryRepository;
    private final SupplierOutboxEventWriter outboxWriter;
    private final Clock clock;

    /** How many times one import may be re-emitted before the owner refuses and says so. */
    @Value("${pos.supplier.pricat.republish-max-attempts:3}")
    private int maxAttempts;

    /** Minimum gap between two re-emits of the same import. */
    @Value("${pos.supplier.pricat.republish-cooldown:PT10M}")
    private Duration cooldown;

    /**
     * Re-emits the named import's chunk and completion events.
     *
     * @return the number of chunk events queued; {@code 0} when the request could not be served,
     *         which is always logged with the reason
     */
    @Transactional
    public int republish(@NonNull SupplierPriceCatalogRepublishRequestedV1 request) {
        Optional<PriceCatalogImportEntity> found = importRepository.findById(request.importManifestId());
        if (found.isEmpty()) {
            // Not retryable and not this module's defect to fix: the requester named an import that
            // was never staged here. Recording it and moving on beats blocking the partition.
            log.error(
                    "Cannot re-publish PRICAT import {} requested by {}: no such import",
                    request.importManifestId(),
                    request.requestedBy());
            return 0;
        }

        PriceCatalogImportEntity manifest = found.get();
        if (!manifest.getVendorProfileId().equals(request.vendorProfileId())) {
            log.error(
                    "Refusing to re-publish PRICAT import {}: request names profile {} but the import belongs to {}",
                    manifest.getImportManifestId(),
                    request.vendorProfileId(),
                    manifest.getVendorProfileId());
            return 0;
        }
        if (!isRepublishable(manifest.getStatus())) {
            // A FAILED import staged no lines, so there is nothing to re-emit and never will be.
            // The vendor's next fetch is the only thing that can help.
            log.warn(
                    "Refusing to re-publish PRICAT import {}: status is {}",
                    manifest.getImportManifestId(),
                    manifest.getStatus());
            return 0;
        }

        Instant now = Instant.now(clock);
        if (manifest.getRepublishCount() >= maxAttempts) {
            log.error(
                    "PRICAT import {} has been re-published {} times and {} is still short ({} of {} chunks)."
                            + " Refusing further re-emits; the consumer needs investigating",
                    manifest.getImportManifestId(),
                    manifest.getRepublishCount(),
                    request.requestedBy(),
                    request.chunksApplied(),
                    request.expectedChunks());
            return 0;
        }
        Instant last = manifest.getLastRepublishedAt();
        if (last != null && Duration.between(last, now).compareTo(cooldown) < 0) {
            // A consumer processing several completion events in a row can ask more than once
            // before the first re-emit has even drained the outbox. Answering each would multiply
            // the catalogue on the topic without changing anything the consumer sees.
            log.info(
                    "Skipping re-publication of PRICAT import {}: last re-emit was {} ago, cooldown is {}",
                    manifest.getImportManifestId(),
                    Duration.between(last, now),
                    cooldown);
            return 0;
        }

        int chunks = emit(manifest, now);
        manifest.setRepublishCount(manifest.getRepublishCount() + 1);
        manifest.setLastRepublishedAt(now);
        importRepository.save(manifest);

        log.warn(
                "Re-published PRICAT import {} for {}: {} chunk events re-emitted after {} of {} chunks applied"
                        + " (attempt {} of {})",
                manifest.getImportManifestId(),
                request.requestedBy(),
                chunks,
                request.chunksApplied(),
                request.expectedChunks(),
                manifest.getRepublishCount(),
                maxAttempts);
        return chunks;
    }

    /**
     * Queues the chunk events followed by the completion event, exactly as the original publication
     * ordered them.
     *
     * <p>Read and queued one chunk at a time: a country-wide catalogue is tens of thousands of
     * lines, and holding all of them to re-emit a recovery would make the recovery itself the
     * outage.
     */
    private int emit(PriceCatalogImportEntity manifest, Instant occurredAt) {
        String topic = DomainTopics.events("supplier");
        int chunkCount = manifest.getChunkCount();

        for (int sequence = 1; sequence <= chunkCount; sequence++) {
            List<PriceCatalogEntryEntity> entries =
                    entryRepository.findByImportManifestIdAndChunkSequenceOrderByPositionNumberAscEntryIdAsc(
                            manifest.getImportManifestId(), sequence);
            if (entries.isEmpty()) {
                // The manifest declares a chunk whose lines are not in the table. Re-emitting the
                // rest would hand the consumer a set that satisfies its chunk count while missing
                // lines, turning a visible gap into an invisible one.
                throw new IllegalStateException("PRICAT import " + manifest.getImportManifestId() + " declares "
                        + chunkCount + " chunks but chunk " + sequence + " has no staged lines");
            }
            SupplierPriceCatalogUpdatedV1 payload = PriceCatalogEventFactory.chunkPayload(
                    manifest,
                    sequence,
                    chunkCount,
                    entries.stream().map(PriceCatalogRepublisher::toEventLine).toList());
            outboxWriter.publish(
                    topic,
                    PriceCatalogEventFactory.envelope(
                            manifest, SupplierPriceCatalogUpdatedV1.EVENT_TYPE, sequence, occurredAt, payload));
        }

        // The completion carries the import's own completedAt, not this instant: a re-emit
        // re-delivers a fact, it does not restate when the fact happened. The consumer needs the
        // declared chunk total again to re-evaluate completeness, and if it is still short after
        // this it will ask again — which the attempt cap above is what bounds.
        Instant completedAt = manifest.getCompletedAt() == null ? occurredAt : manifest.getCompletedAt();
        outboxWriter.publish(
                topic,
                PriceCatalogEventFactory.envelope(
                        manifest,
                        SupplierPriceCatalogImportCompletedV1.EVENT_TYPE,
                        chunkCount + 1L,
                        occurredAt,
                        PriceCatalogEventFactory.completionPayload(manifest, completedAt)));
        return chunkCount;
    }

    private static boolean isRepublishable(PriceCatalogImportStatus status) {
        return status == PriceCatalogImportStatus.COMPLETED || status == PriceCatalogImportStatus.EMPTY;
    }

    /**
     * Rebuilds the event line from the staged row.
     *
     * <p>The staged row is the record of what was published, so the re-emitted line is the original
     * line rather than a fresh reading of the vendor document — which is the point of staging at
     * all (ADR-0053 §1).
     */
    private static SupplierPriceCatalogLine toEventLine(PriceCatalogEntryEntity entry) {
        return new SupplierPriceCatalogLine(
                entry.getMatchedProductId(),
                entry.getMatchMethod().name(),
                entry.getArticleEan(),
                entry.getSupplierArticleCode(),
                entry.getXReferenceCode(),
                entry.getSuggestedRetailPrice(),
                entry.getGrossPrice(),
                entry.getNetPrice(),
                entry.getTaxRate(),
                entry.getRecyclingFee(),
                entry.getEffectiveFrom(),
                entry.getPositionNumber());
    }
}
