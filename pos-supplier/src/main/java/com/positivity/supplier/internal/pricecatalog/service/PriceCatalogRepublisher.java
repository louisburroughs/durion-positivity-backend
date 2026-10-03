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
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
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
    private final EntityManager entityManager;

    /** How many times one import may be re-emitted before the owner refuses and says so. */
    @Value("${pos.supplier.pricat.republish-max-attempts:3}")
    private int maxAttempts;

    /** Minimum gap between two re-emits of the same import. */
    @Value("${pos.supplier.pricat.republish-cooldown:PT10M}")
    private Duration cooldown;

    /**
     * Re-emits the named import's chunk and completion events.
     *
     * <p>One transaction, joined from the caller, so the re-emitted events, the attempt counter and
     * the command's {@code processed_events} mark commit or roll back together. The persistence
     * context is flushed and <strong>cleared</strong> after every chunk: a caller must not rely on
     * an entity it loaded earlier in the same transaction still being managed after this returns.
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

        if (!hasEveryChunkItDeclared(manifest)) {
            // Checked before anything is queued, not discovered part-way through publishing. The
            // events and the counter share this transaction, so a failure mid-emit would roll the
            // whole thing back — but only after the listener had already decided how to treat the
            // command, and only for a condition no retry can fix. Refusing up front turns our own
            // inconsistent staged data into a plain, logged refusal.
            return 0;
        }

        // Recorded before the chunks are queued, while the manifest is still managed: emit() clears
        // the persistence context after every chunk, which would detach it and leave a later change
        // unwritten. The first flush writes it; a failure on any chunk rolls it back with the events.
        manifest.setRepublishCount(manifest.getRepublishCount() + 1);
        manifest.setLastRepublishedAt(now);
        importRepository.save(manifest);
        int chunks = emit(manifest, now);

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
     *
     * <p>Reading one chunk at a time bounds each query, not the heap: one transaction is one
     * persistence context, and it would hold every staged line read and every outbox row queued
     * until commit. So each chunk's outbox row is flushed and the context cleared before the next
     * chunk is read. The transaction is not split: the flushed rows stay uncommitted, and a failure
     * on a later chunk rolls every one of them back. The manifest is detached by the first clear and
     * is only read afterwards; its fields are plain columns, so nothing lazy is touched.
     */
    private int emit(PriceCatalogImportEntity manifest, Instant occurredAt) {
        String topic = DomainTopics.events("supplier");
        int chunkCount = manifest.getChunkCount();

        for (int sequence = 1; sequence <= chunkCount; sequence++) {
            List<PriceCatalogEntryEntity> entries =
                    entryRepository.findByImportManifestIdAndChunkSequenceOrderByPositionNumberAscEntryIdAsc(
                            manifest.getImportManifestId(), sequence);
            if (entries.isEmpty()) {
                // Unreachable: hasEveryChunkItDeclared() has already compared the staged sequences
                // against the declared total in this same transaction. Kept as an assertion because
                // publishing past it would emit a partial catalogue that looks whole to a consumer,
                // and a loud failure is the only acceptable answer to that.
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
            entityManager.flush();
            entityManager.clear();
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

    /**
     * Whether the staged lines still cover every chunk the manifest declared.
     *
     * <p>A re-emit that publishes only the chunks it happens to find would hand the consumer a set
     * that satisfies its chunk count while lines stayed missing — turning a gap it can see into one
     * it cannot. Refusing the whole re-emit keeps the import visibly incomplete instead.
     */
    private boolean hasEveryChunkItDeclared(PriceCatalogImportEntity manifest) {
        List<Integer> staged = entryRepository.findDistinctChunkSequences(manifest.getImportManifestId());
        List<Integer> declared =
                IntStream.rangeClosed(1, manifest.getChunkCount()).boxed().toList();
        if (staged.equals(declared)) {
            return true;
        }
        log.error(
                "Refusing to re-publish PRICAT import {}: it declares {} chunks but its staged lines cover {}",
                manifest.getImportManifestId(),
                manifest.getChunkCount(),
                staged);
        return false;
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
